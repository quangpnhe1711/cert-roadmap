package com.certcopilot.platform.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * Original uploaded files live behind this port. Buckets are private; the only
 * way to reach a file is a short-lived signed URL issued after an ownership
 * check, so no public URL for user material ever exists.
 */
public interface ObjectStoragePort {

    void put(String key, InputStream content, long contentLength, String contentType);

    InputStream get(String key);

    boolean exists(String key);

    void delete(String key);

    /** Short-lived, authenticated read URL for the in-app viewer. */
    String signedReadUrl(String key, Duration ttl);
}
