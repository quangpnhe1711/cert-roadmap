package com.certcopilot.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Filesystem-backed storage for local development.
 *
 * <p>An S3-compatible adapter is the production counterpart; both sit behind
 * {@link ObjectStoragePort}, so nothing in the domain changes when the
 * deployment target is decided.
 *
 * <p>Signed URLs are real HMAC signatures with an expiry rather than a stub,
 * because the access-control behaviour is the part worth exercising in dev: a
 * link that leaks must stop working.
 */
@Component
@ConditionalOnProperty(name = "app.storage.adapter", havingValue = "local", matchIfMissing = true)
public class LocalFileSystemStorage implements ObjectStoragePort {

    private final Path root;
    private final byte[] signingKey;

    public LocalFileSystemStorage(StorageProperties props) {
        this.root = Path.of(props.getLocalRoot()).toAbsolutePath().normalize();
        // Dev-only key. Production uses the storage provider's own signing.
        this.signingKey = "certcopilot-local-dev-signing-key".getBytes(StandardCharsets.UTF_8);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create storage root " + root, e);
        }
    }

    @Override
    public void put(String key, InputStream content, long contentLength, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("cannot write " + key, e);
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException e) {
            throw new StorageException("cannot read " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new StorageException("cannot delete " + key, e);
        }
    }

    @Override
    public String signedReadUrl(String key, Duration ttl) {
        long expiresAt = Instant.now().plus(ttl).getEpochSecond();
        String signature = sign(key + ":" + expiresAt);
        return "/api/v1/files/" + urlEncode(key) + "?expires=" + expiresAt + "&sig=" + signature;
    }

    /** Verifies a signature produced by {@link #signedReadUrl}. */
    public boolean verifySignature(String key, long expiresAt, String signature) {
        if (Instant.now().getEpochSecond() > expiresAt) {
            return false;
        }
        return constantTimeEquals(sign(key + ":" + expiresAt), signature);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new StorageException("cannot sign url", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static String urlEncode(String key) {
        return java.net.URLEncoder.encode(key, StandardCharsets.UTF_8);
    }

    /** Guards against a key escaping the storage root via traversal. */
    private Path resolve(String key) {
        Path candidate = root.resolve(key).normalize();
        if (!candidate.startsWith(root)) {
            throw new StorageException("illegal storage key: " + key, null);
        }
        return candidate;
    }

    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
