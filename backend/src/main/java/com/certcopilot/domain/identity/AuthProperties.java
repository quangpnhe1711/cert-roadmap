package com.certcopilot.domain.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /**
     * HMAC signing secret. Overridden per environment; the default exists only so
     * a fresh clone runs, and is refused when the "prod" profile is active.
     */
    private String jwtSecret = "dev-only-secret-change-me-0123456789-0123456789";

    /** Short-lived so a leaked access token expires quickly. */
    private long accessTokenTtlSeconds = 900;

    /** Rotated on every refresh and revocable, which pure JWT cannot do. */
    private long refreshTokenTtlSeconds = 60L * 60 * 24 * 30;

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String v) { this.jwtSecret = v; }
    public long getAccessTokenTtlSeconds() { return accessTokenTtlSeconds; }
    public void setAccessTokenTtlSeconds(long v) { this.accessTokenTtlSeconds = v; }
    public long getRefreshTokenTtlSeconds() { return refreshTokenTtlSeconds; }
    public void setRefreshTokenTtlSeconds(long v) { this.refreshTokenTtlSeconds = v; }
}
