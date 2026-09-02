package com.certcopilot.platform.jobs;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Polls the job table and dispatches to handlers.
 *
 * <p>Runs inside the API deployable today. Setting {@code app.worker.enabled=false}
 * turns it off, which is how the worker is split into its own process later
 * without a code change.
 *
 * <p>Uses a bounded platform-thread pool. Stage 2 specified Java 21 virtual
 * threads for this pool; the build currently targets Java 17 (see README).
 * At MVP volume - tens of jobs per minute - a bounded pool is sufficient, and
 * the switch is one executor factory call.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobQueue queue;
    private final JobProperties props;
    private final Map<String, JobHandler> handlers;
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    private ScheduledExecutorService scheduler;
    private ExecutorService executor;

    public JobWorker(JobQueue queue, JobProperties props, List<JobHandler> handlerList) {
        this.queue = queue;
        this.props = props;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(JobHandler::jobType, Function.identity()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.isEnabled()) {
            log.info("job worker disabled by configuration");
            return;
        }
        this.executor = Executors.newFixedThreadPool(props.getThreads());
        this.scheduler = Executors.newScheduledThreadPool(2);

        scheduler.scheduleWithFixedDelay(this::pollOnce,
                props.getPollIntervalMs(), props.getPollIntervalMs(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::recoverOnce,
                props.getRecoveryIntervalMs(), props.getRecoveryIntervalMs(), TimeUnit.MILLISECONDS);

        log.info("job worker {} started, handlers={}, threads={}",
                workerId, handlers.keySet(), props.getThreads());
    }

    /** Visible for tests: claims and runs one batch synchronously. */
    public int pollOnce() {
        try {
            List<JobRecord> claimed = queue.claim(
                    workerId, props.getBatchSize(), Duration.ofMillis(props.getLeaseDurationMs()));
            for (JobRecord job : claimed) {
                executor.submit(() -> run(job));
            }
            return claimed.size();
        } catch (Exception e) {
            log.error("job poll failed", e);
            return 0;
        }
    }

    /** Visible for tests: runs one stale-lease recovery pass synchronously. */
    public int recoverOnce() {
        try {
            return queue.recoverStaleLeases();
        } catch (Exception e) {
            log.error("stale lease recovery failed", e);
            return 0;
        }
    }

    /** Visible for tests: executes a claimed job on the calling thread. */
    public void run(JobRecord job) {
        JobHandler handler = handlers.get(job.type());
        if (handler == null) {
            queue.fail(job.id(), job.maxAttempts(), job.maxAttempts(),
                    "no handler registered for type " + job.type());
            return;
        }
        long started = System.currentTimeMillis();
        try {
            handler.handle(job);
            queue.succeed(job.id());
            log.debug("job {} type={} succeeded in {}ms",
                    job.id(), job.type(), System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.warn("job {} type={} attempt {}/{} failed: {}",
                    job.id(), job.type(), job.attempts(), job.maxAttempts(), e.toString());
            queue.fail(job.id(), job.attempts(), job.maxAttempts(), e.toString());
        }
    }

    public String workerId() {
        return workerId;
    }

    @PreDestroy
    public void stop() {
        // Graceful shutdown: stop claiming, then give running jobs a bounded
        // window to finish. Anything still running recovers via lease expiry.
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }
}
