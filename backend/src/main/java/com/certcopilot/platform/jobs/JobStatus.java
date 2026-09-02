package com.certcopilot.platform.jobs;

/** Lifecycle of a queued unit of asynchronous work (System Design, A4). */
public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    /** Retryable failure that has not yet exhausted attempts. */
    FAILED,
    /** Terminal: attempts exhausted. Surfaced to admin, and to the user when it affects them. */
    DEAD,
    CANCELLED
}
