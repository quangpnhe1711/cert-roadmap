package com.certcopilot.platform.jobs;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.worker")
public class JobProperties {

    /**
     * Worker and API share one deployable today. Setting this false on one
     * instance and api.enabled false on another splits them with no code change.
     */
    private boolean enabled = true;
    private long pollIntervalMs = 500;
    private int batchSize = 5;
    private long leaseDurationMs = 120_000;
    private long heartbeatIntervalMs = 30_000;
    private long recoveryIntervalMs = 15_000;
    private int threads = 4;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long v) { this.pollIntervalMs = v; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int v) { this.batchSize = v; }
    public long getLeaseDurationMs() { return leaseDurationMs; }
    public void setLeaseDurationMs(long v) { this.leaseDurationMs = v; }
    public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public void setHeartbeatIntervalMs(long v) { this.heartbeatIntervalMs = v; }
    public long getRecoveryIntervalMs() { return recoveryIntervalMs; }
    public void setRecoveryIntervalMs(long v) { this.recoveryIntervalMs = v; }
    public int getThreads() { return threads; }
    public void setThreads(int v) { this.threads = v; }
}
