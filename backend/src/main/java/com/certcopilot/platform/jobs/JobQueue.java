package com.certcopilot.platform.jobs;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL-backed job queue (System Design, section 10 and correction A4).
 *
 * <p>No broker. Claiming uses {@code FOR UPDATE SKIP LOCKED} so several workers
 * can poll the same table without contending. A lease plus a recovery sweep
 * means a worker that dies does not strand its job.
 */
@Component
public class JobQueue {

    private static final Logger log = LoggerFactory.getLogger(JobQueue.class);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final JdbcTemplate jdbc;

    public JobQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Transactional outbox: call this inside the same transaction as the domain
     * state change that requires the work. A rollback discards the job with it;
     * a commit guarantees the job exists.
     *
     * <p>Idempotent on {@code dedupeKey} so a retried caller cannot double-enqueue.
     *
     * @return true when a new job row was inserted, false when the key already existed
     */
    public boolean enqueue(String type, String dedupeKey, String payloadJson, int maxAttempts) {
        int inserted = jdbc.update(
                "INSERT INTO job (id, type, payload, dedupe_key, status, max_attempts, available_at) "
                        + "VALUES (?, ?, ?::jsonb, ?, 'PENDING', ?, now()) "
                        + "ON CONFLICT (dedupe_key) DO NOTHING",
                UUID.randomUUID(), type, payloadJson, dedupeKey, maxAttempts);
        if (inserted == 0) {
            log.debug("job already enqueued, dedupeKey={}", dedupeKey);
        }
        return inserted == 1;
    }

    /**
     * Claims up to {@code batchSize} due jobs for this worker.
     *
     * <p>{@code attempts} is incremented at claim time, not at failure time, so a
     * worker that dies without reporting anything still consumes an attempt and
     * cannot loop forever.
     */
    public List<JobRecord> claim(String workerId, int batchSize, Duration leaseDuration) {
        Timestamp leaseUntil = Timestamp.from(Instant.now().plus(leaseDuration));
        return jdbc.query(
                "UPDATE job "
                        + "   SET status = 'RUNNING', "
                        + "       attempts = attempts + 1, "
                        + "       worker_id = ?, "
                        + "       locked_at = now(), "
                        + "       lease_expires_at = ?, "
                        + "       updated_at = now() "
                        + " WHERE id IN ( "
                        + "     SELECT id FROM job "
                        + "      WHERE status = 'PENDING' AND available_at <= now() "
                        + "      ORDER BY available_at "
                        + "        FOR UPDATE SKIP LOCKED "
                        + "      LIMIT ? "
                        + " ) "
                        + "RETURNING id, type, payload, dedupe_key, status, attempts, max_attempts, "
                        + "          available_at, lease_expires_at, worker_id",
                JOB_MAPPER, workerId, leaseUntil, batchSize);
    }

    /** Extends the lease of a running job. Long handlers call this periodically. */
    public boolean heartbeat(UUID jobId, String workerId, Duration leaseDuration) {
        Timestamp leaseUntil = Timestamp.from(Instant.now().plus(leaseDuration));
        return jdbc.update(
                "UPDATE job SET lease_expires_at = ?, updated_at = now() "
                        + " WHERE id = ? AND worker_id = ? AND status = 'RUNNING'",
                leaseUntil, jobId, workerId) == 1;
    }

    public void succeed(UUID jobId) {
        jdbc.update(
                "UPDATE job "
                        + "   SET status = 'SUCCEEDED', completed_at = now(), updated_at = now(), "
                        + "       worker_id = NULL, locked_at = NULL, lease_expires_at = NULL "
                        + " WHERE id = ?",
                jobId);
    }

    /**
     * Records a failed attempt. Reschedules with exponential backoff, or moves the
     * job to the terminal DEAD state once attempts are exhausted.
     */
    public void fail(UUID jobId, int attempts, int maxAttempts, String error) {
        boolean dead = attempts >= maxAttempts;
        Timestamp nextRun = Timestamp.from(Instant.now().plus(backoff(attempts)));
        jdbc.update(
                "UPDATE job "
                        + "   SET status = ?, available_at = ?, last_error = ?, updated_at = now(), "
                        + "       worker_id = NULL, locked_at = NULL, lease_expires_at = NULL, "
                        + "       completed_at = CASE WHEN ? THEN now() ELSE NULL END "
                        + " WHERE id = ?",
                dead ? JobStatus.DEAD.name() : JobStatus.PENDING.name(),
                nextRun, truncate(error), dead, jobId);
        if (dead) {
            log.error("job {} moved to DEAD after {} attempts: {}", jobId, attempts, truncate(error));
        }
    }

    /**
     * Returns jobs whose worker died to the queue. A worker crash, an OOM kill or
     * a deploy mid-run all recover through this path.
     *
     * @return number of jobs recovered
     */
    public int recoverStaleLeases() {
        List<UUID> stale = jdbc.queryForList(
                "SELECT id FROM job WHERE status = 'RUNNING' AND lease_expires_at < now()",
                UUID.class);
        if (stale.isEmpty()) {
            return 0;
        }
        int recovered = jdbc.update(
                "UPDATE job "
                        + "   SET status = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'PENDING' END, "
                        + "       available_at = now(), "
                        + "       worker_id = NULL, locked_at = NULL, lease_expires_at = NULL, "
                        + "       last_error = COALESCE(last_error, 'lease expired'), "
                        + "       updated_at = now() "
                        + " WHERE status = 'RUNNING' AND lease_expires_at < now()");
        log.warn("recovered {} job(s) from expired leases: {}", recovered, stale);
        return recovered;
    }

    public JobRecord findById(UUID jobId) {
        List<JobRecord> rows = jdbc.query(
                "SELECT id, type, payload, dedupe_key, status, attempts, max_attempts, "
                        + "       available_at, lease_expires_at, worker_id "
                        + "  FROM job WHERE id = ?",
                JOB_MAPPER, jobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Exponential backoff with jitter, capped at one hour. */
    static Duration backoff(int attempts) {
        long seconds = (long) Math.min(Math.pow(2, Math.max(attempts, 1)) * 30, MAX_BACKOFF.toSeconds());
        long spread = Math.max(1, seconds / 5);
        long jitter = ThreadLocalRandom.current().nextLong(-spread, spread + 1);
        return Duration.ofSeconds(Math.max(1, seconds + jitter));
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }

    private static final RowMapper<JobRecord> JOB_MAPPER = (rs, rowNum) -> new JobRecord(
            rs.getObject("id", UUID.class),
            rs.getString("type"),
            rs.getString("payload"),
            rs.getString("dedupe_key"),
            JobStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            rs.getInt("max_attempts"),
            rs.getTimestamp("available_at").toInstant(),
            rs.getTimestamp("lease_expires_at") == null
                    ? null : rs.getTimestamp("lease_expires_at").toInstant(),
            rs.getString("worker_id"));
}
