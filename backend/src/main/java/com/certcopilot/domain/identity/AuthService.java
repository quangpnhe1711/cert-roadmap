package com.certcopilot.domain.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login and token rotation.
 *
 * <p>Access tokens are short-lived JWTs; refresh tokens are opaque, stored
 * hashed, rotated on use and revocable. Pure stateless JWT was rejected because
 * logout and "sign out this device" have to actually work, and a mobile client
 * will need per-device sessions.
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AuthProperties props;
    private final PasswordEncoder passwordEncoder;
    private final SecretKey signingKey;

    public AuthService(JdbcTemplate jdbc, AuthProperties props) {
        this.jdbc = jdbc;
        this.props = props;
        // Argon2id with Spring Security's recommended parameters.
        this.passwordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        this.signingKey = Keys.hmacShaKeyFor(
                props.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public Tokens register(String email, String password, String displayName) {
        String normalised = normaliseEmail(email);
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE email = ? AND deleted_at IS NULL",
                Integer.class, normalised);
        if (existing != null && existing > 0) {
            throw new AuthException("EMAIL_TAKEN", "email already registered");
        }
        if (password == null || password.length() < 8) {
            throw new AuthException("WEAK_PASSWORD", "password must be at least 8 characters");
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user (id, email, password_hash, display_name) VALUES (?,?,?,?)",
                id, normalised, passwordEncoder.encode(password),
                displayName == null || displayName.isBlank() ? normalised : displayName.strip());

        return issueTokens(id);
    }

    @Transactional
    public Tokens login(String email, String password) {
        String normalised = normaliseEmail(email);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, password_hash FROM app_user WHERE email = ? AND deleted_at IS NULL",
                normalised);
        if (rows.isEmpty()) {
            // Same error either way: revealing which emails exist is a free gift
            // to anyone enumerating accounts.
            throw new AuthException("INVALID_CREDENTIALS", "email or password is incorrect");
        }
        Map<String, Object> row = rows.get(0);
        if (!passwordEncoder.matches(password, (String) row.get("password_hash"))) {
            throw new AuthException("INVALID_CREDENTIALS", "email or password is incorrect");
        }
        return issueTokens((UUID) row.get("id"));
    }

    /**
     * Rotates the refresh token: the presented one is revoked as it is redeemed.
     *
     * <p>{@code noRollbackFor} is load-bearing. Detecting a replayed token revokes
     * every session for that user and then throws - and a plain rollback would
     * undo the revocation along with the exception that triggered it, leaving the
     * stolen sessions alive while the log claimed otherwise. Here the write is the
     * security response, so it has to outlive the failure.
     */
    @Transactional(noRollbackFor = AuthException.class)
    public Tokens refresh(String refreshToken) {
        String hash = hashToken(refreshToken);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, user_id, expires_at, revoked_at FROM refresh_token WHERE token_hash = ?",
                hash);
        if (rows.isEmpty()) {
            throw new AuthException("INVALID_REFRESH_TOKEN", "refresh token is not recognised");
        }
        Map<String, Object> row = rows.get(0);
        if (row.get("revoked_at") != null) {
            // A revoked token being replayed suggests theft; drop every session
            // for that user rather than only refusing this one.
            UUID userId = (UUID) row.get("user_id");
            revokeAll(userId);
            throw new AuthException("REFRESH_TOKEN_REUSED", "refresh token was already used");
        }
        Timestamp expiresAt = (Timestamp) row.get("expires_at");
        if (expiresAt.toInstant().isBefore(Instant.now())) {
            throw new AuthException("REFRESH_TOKEN_EXPIRED", "refresh token has expired");
        }

        jdbc.update("UPDATE refresh_token SET revoked_at = now() WHERE id = ?", row.get("id"));
        return issueTokens((UUID) row.get("user_id"));
    }

    @Transactional
    public void logout(String refreshToken) {
        jdbc.update("UPDATE refresh_token SET revoked_at = now() "
                + " WHERE token_hash = ? AND revoked_at IS NULL", hashToken(refreshToken));
    }

    @Transactional
    public void revokeAll(UUID userId) {
        jdbc.update("UPDATE refresh_token SET revoked_at = now() "
                + " WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    /** @return the user id carried by a valid access token */
    public UUID verifyAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return UUID.fromString(claims.getSubject());
        } catch (Exception e) {
            throw new AuthException("INVALID_ACCESS_TOKEN", "access token is not valid");
        }
    }

    public AppUser findById(UUID userId) {
        List<AppUser> users = jdbc.query(
                "SELECT id, email, display_name, locale, created_at FROM app_user "
                        + " WHERE id = ? AND deleted_at IS NULL",
                (rs, n) -> new AppUser(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("locale"),
                        rs.getTimestamp("created_at").toInstant()),
                userId);
        return users.isEmpty() ? null : users.get(0);
    }

    private Tokens issueTokens(UUID userId) {
        Instant now = Instant.now();
        String accessToken = Jwts.builder()
                .subject(userId.toString())
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plusSeconds(props.getAccessTokenTtlSeconds())))
                .signWith(signingKey)
                .compact();

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        jdbc.update(
                "INSERT INTO refresh_token (id, user_id, token_hash, expires_at) VALUES (?,?,?,?)",
                UUID.randomUUID(), userId, hashToken(refreshToken),
                Timestamp.from(now.plusSeconds(props.getRefreshTokenTtlSeconds())));

        return new Tokens(accessToken, refreshToken, props.getAccessTokenTtlSeconds(), userId);
    }

    /** Refresh tokens are stored hashed so a database read does not yield sessions. */
    private static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String normaliseEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new AuthException("INVALID_EMAIL", "email is required");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds, UUID userId) {
    }
}
