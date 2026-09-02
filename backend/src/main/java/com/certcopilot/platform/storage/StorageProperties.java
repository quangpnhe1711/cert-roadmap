package com.certcopilot.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** "local" for development; an S3-compatible adapter slots in behind the port. */
    private String adapter = "local";
    private String localRoot = "./var/storage";
    private long maxUploadBytes = 200L * 1024 * 1024;
    private int maxPages = 2000;
    private long signedUrlTtlSeconds = 900;

    public String getAdapter() { return adapter; }
    public void setAdapter(String v) { this.adapter = v; }
    public String getLocalRoot() { return localRoot; }
    public void setLocalRoot(String v) { this.localRoot = v; }
    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long v) { this.maxUploadBytes = v; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int v) { this.maxPages = v; }
    public long getSignedUrlTtlSeconds() { return signedUrlTtlSeconds; }
    public void setSignedUrlTtlSeconds(long v) { this.signedUrlTtlSeconds = v; }
}
