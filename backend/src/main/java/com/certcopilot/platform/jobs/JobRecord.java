package com.certcopilot.platform.jobs;

import java.time.Instant;
import java.util.UUID;

/**
 * A claimed row from the job table. Carries the lease fields so a handler can
 * heartbeat and so the recovery sweep can reason about staleness.
 */
public record JobRecord(
        UUID id,
        String type,
        String payload,
        String dedupeKey,
        JobStatus status,
        int attempts,
        int maxAttempts,
        Instant availableAt,
        Instant leaseExpiresAt,
        String workerId
) {
    public boolean attemptsExhausted() {
        return attempts >= maxAttempts;
    }
}
