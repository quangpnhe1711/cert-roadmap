package com.certcopilot.platform.jobs;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.certcopilot.support.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correction A4: the job queue's recovery semantics, verified against a real
 * PostgreSQL. These behaviours are the reason no message broker is needed.
 */
class JobQueueIT extends PostgresSupport {

    @Autowired
    private JobQueue queue;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        requireDatabase();
        jdbc.update("DELETE FROM job");
    }

    @Test
    @DisplayName("enqueue is idempotent on the dedupe key")
    void enqueueIsIdempotent() {
        String key = "pack:plan-1:unit-42:v3";

        assertThat(queue.enqueue("GENERATE_PACK", key, "{}", 3)).isTrue();
        assertThat(queue.enqueue("GENERATE_PACK", key, "{}", 3))
                .as("a retried caller must not create a second job")
                .isFalse();

        assertThat(count("SELECT count(*) FROM job WHERE dedupe_key = ?", key)).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent workers never claim the same job twice")
    void skipLockedPreventsDoubleClaim() throws Exception {
        int jobCount = 40;
        IntStream.range(0, jobCount)
                .forEach(i -> queue.enqueue("GENERATE_PACK", "job-" + i, "{}", 3));

        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Callable<List<JobRecord>>> tasks = IntStream.range(0, workers)
                .mapToObj(i -> (Callable<List<JobRecord>>) () ->
                        queue.claim("worker-" + i, 10, Duration.ofMinutes(2)))
                .collect(Collectors.toList());

        List<UUID> claimedIds = pool.invokeAll(tasks).stream()
                .flatMap(f -> {
                    try {
                        return f.get().stream();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(JobRecord::id)
                .toList();
        pool.shutdown();

        assertThat(claimedIds)
                .as("FOR UPDATE SKIP LOCKED must hand each job to exactly one worker")
                .doesNotHaveDuplicates();
        assertThat(claimedIds).hasSize(jobCount);
    }

    @Test
    @DisplayName("attempts increment at claim time, so a silent worker death still counts")
    void attemptsIncrementOnClaim() {
        queue.enqueue("GENERATE_PACK", "attempt-test", "{}", 3);

        JobRecord first = queue.claim("w1", 1, Duration.ofMinutes(2)).get(0);
        assertThat(first.attempts()).isEqualTo(1);

        expireLease(first.id());
        queue.recoverStaleLeases();

        JobRecord second = queue.claim("w2", 1, Duration.ofMinutes(2)).get(0);
        assertThat(second.attempts())
                .as("a worker that died without reporting must still consume an attempt")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an expired lease returns the job to the queue")
    void staleLeaseIsRecovered() {
        queue.enqueue("GENERATE_PACK", "lease-test", "{}", 3);
        JobRecord claimed = queue.claim("dying-worker", 1, Duration.ofMinutes(2)).get(0);
        assertThat(status(claimed.id())).isEqualTo("RUNNING");

        expireLease(claimed.id());
        assertThat(queue.recoverStaleLeases()).isEqualTo(1);

        assertThat(status(claimed.id())).isEqualTo("PENDING");
        assertThat(queue.claim("healthy-worker", 1, Duration.ofMinutes(2)))
                .as("the recovered job must be claimable again")
                .hasSize(1);
    }

    @Test
    @DisplayName("an expired lease on an exhausted job becomes DEAD, not an infinite loop")
    void exhaustedJobBecomesDead() {
        queue.enqueue("GENERATE_PACK", "dead-test", "{}", 1);
        JobRecord claimed = queue.claim("dying-worker", 1, Duration.ofMinutes(2)).get(0);
        expireLease(claimed.id());

        queue.recoverStaleLeases();

        assertThat(status(claimed.id()))
                .as("max_attempts reached, so recovery must terminate rather than requeue")
                .isEqualTo("DEAD");
    }

    @Test
    @DisplayName("a failed attempt is rescheduled, and the last one is terminal")
    void failureSchedulesRetryThenDies() {
        queue.enqueue("GENERATE_PACK", "fail-test", "{}", 2);
        JobRecord first = queue.claim("w1", 1, Duration.ofMinutes(2)).get(0);

        queue.fail(first.id(), first.attempts(), first.maxAttempts(), "transient error");
        assertThat(status(first.id())).isEqualTo("PENDING");

        queue.fail(first.id(), 2, 2, "still broken");
        assertThat(status(first.id())).isEqualTo("DEAD");
    }

    @Test
    @DisplayName("heartbeat extends the lease of a running job")
    void heartbeatExtendsLease() {
        queue.enqueue("MATERIAL_PROCESS", "heartbeat-test", "{}", 3);
        JobRecord claimed = queue.claim("w1", 1, Duration.ofSeconds(5)).get(0);

        assertThat(queue.heartbeat(claimed.id(), "w1", Duration.ofMinutes(10))).isTrue();
        assertThat(queue.heartbeat(claimed.id(), "someone-else", Duration.ofMinutes(10)))
                .as("only the owning worker may extend a lease")
                .isFalse();
    }

    private void expireLease(UUID jobId) {
        jdbc.update("UPDATE job SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", jobId);
    }

    private String status(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, jobId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
