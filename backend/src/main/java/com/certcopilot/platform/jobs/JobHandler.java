package com.certcopilot.platform.jobs;

/**
 * Handles one job type. Implementations MUST be idempotent: a job can be
 * delivered more than once when a worker dies mid-run and its lease expires.
 */
public interface JobHandler {

    /** The job type string this handler claims. */
    String jobType();

    /**
     * Executes the job. Throwing marks the attempt failed and schedules a retry
     * until attempts are exhausted, after which the job becomes DEAD.
     */
    void handle(JobRecord job) throws Exception;
}
