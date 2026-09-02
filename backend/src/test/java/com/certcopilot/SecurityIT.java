package com.certcopilot;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.domain.identity.AuthException;
import com.certcopilot.domain.identity.AuthProperties;
import com.certcopilot.domain.identity.AuthService;
import com.certcopilot.domain.material.MaterialService;
import com.certcopilot.domain.planning.PlanService;
import com.certcopilot.platform.storage.LocalFileSystemStorage;
import com.certcopilot.support.PostgresSupport;
import com.certcopilot.support.SyntheticDeck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The security invariants, each expressed as the attack it prevents.
 *
 * <p>Every one of these is enforced in the service layer rather than in a
 * controller, so a job, an admin path or a future mobile client passes the same
 * gate. That is precisely why they need tests at this level: a controller test
 * would prove only that one route is guarded.
 *
 * <p>These do not silently skip in CI - see {@code PostgresSupport.requireDatabase}.
 * A security suite that skipped itself would be worse than no suite, because the
 * build would stay green.
 */
@AutoConfigureMockMvc
class SecurityIT extends PostgresSupport {

    private static final UUID AIF_C01 = UUID.fromString("a1f00000-0000-4000-8000-000000000002");

    @Autowired private AuthService auth;
    @Autowired private PlanService plans;
    @Autowired private MaterialService materials;
    @Autowired private LocalFileSystemStorage storage;
    @Autowired private AuthProperties authProps;
    @Autowired private MockMvc http;
    @Autowired private com.certcopilot.platform.storage.StorageProperties storageProps;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        requireDatabase();
    }

    // ------------------------------------------------- cross-account access

    @Test
    @DisplayName("one learner cannot read another learner's material")
    void materialIsNotReadableAcrossAccounts() throws Exception {
        Learner owner = learnerWithMaterial();
        UUID intruder = register();

        assertThatThrownBy(() -> materials.find(intruder, owner.materialId()))
                .isInstanceOfSatisfying(MaterialService.MaterialException.class, e ->
                        // Same answer as for a material that does not exist: telling
                        // an intruder that an id is real is itself a disclosure.
                        assertThat(e.code()).isEqualTo("MATERIAL_NOT_FOUND"));

        assertThat(materials.listForUser(intruder))
                .as("someone else's upload must not appear in your library")
                .isEmpty();
    }

    @Test
    @DisplayName("one learner cannot obtain a viewer URL for another learner's file")
    void viewerUrlIsNotIssuedAcrossAccounts() throws Exception {
        Learner owner = learnerWithMaterial();
        UUID intruder = register();

        // The signed URL is a bearer credential; issuing one is the same as
        // handing over the file.
        assertThatThrownBy(() -> materials.viewerUrl(intruder, owner.materialId()))
                .isInstanceOf(MaterialService.MaterialException.class);
    }

    @Test
    @DisplayName("one learner cannot open another learner's plan")
    void planIsNotReadableAcrossAccounts() throws Exception {
        Learner owner = learnerWithMaterial();
        UUID intruder = register();

        assertThatThrownBy(() -> plans.requireOwned(intruder, owner.planId()))
                .isInstanceOf(PlanService.PlanException.class);
    }

    @Test
    @DisplayName("no plan-scoped endpoint serves one learner's data to another")
    void planScopedEndpointsAreIsolatedAcrossAccounts() throws Exception {
        Learner owner = learnerWithMaterial();
        String intruderToken = auth.register("intruder-" + UUID.randomUUID() + "@example.com",
                "correct-horse-battery", "Intruder").accessToken();

        // Sweeping the surface rather than one route on purpose: the check has to
        // hold everywhere, and the one endpoint that lacked it looked exactly like
        // the ones that had it.
        List<String> reads = List.of(
                "/api/v1/plans/" + owner.planId(),
                "/api/v1/plans/" + owner.planId() + "/coverage",
                "/api/v1/plans/" + owner.planId() + "/units",
                "/api/v1/plans/" + owner.planId() + "/days",
                "/api/v1/plans/" + owner.planId() + "/analysis",
                "/api/v1/plans/" + owner.planId() + "/units/" + UUID.randomUUID() + "/pack",
                "/api/v1/materials/" + owner.materialId(),
                "/api/v1/materials/" + owner.materialId() + "/viewer-url");

        for (String path : reads) {
            int status = http.perform(get(path).header("Authorization", "Bearer " + intruderToken))
                    .andReturn().getResponse().getStatus();
            assertThat(status)
                    .as("%s must not serve another learner's data", path)
                    .isGreaterThanOrEqualTo(400);
        }

        int quizStatus = http.perform(post("/api/v1/plans/" + owner.planId() + "/quizzes")
                        .header("Authorization", "Bearer " + intruderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DAILY\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(quizStatus)
                .as("starting a quiz on someone else's plan also spends their AI budget")
                .isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("a protected route without a token is refused")
    void unauthenticatedAccessIsRefused() throws Exception {
        assertThat(http.perform(get("/api/v1/plans/" + UUID.randomUUID()))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(http.perform(get("/api/v1/me"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a stored file is not reachable by guessing its path")
    void storedFilesNeedASignature() throws Exception {
        Learner owner = learnerWithMaterial();
        String url = materials.viewerUrl(owner.userId(), owner.materialId());
        String path = url.substring(0, url.indexOf('?'));

        // The endpoint is unauthenticated by design so the browser's PDF viewer can
        // load it; the signature is the entire credential.
        assertThat(http.perform(get(path)).andReturn().getResponse().getStatus())
                .as("no signature, no file")
                .isGreaterThanOrEqualTo(400);
        assertThat(http.perform(get(path + "?expires=99999999999&sig=forged"))
                .andReturn().getResponse().getStatus())
                .isGreaterThanOrEqualTo(400);
    }

    // ------------------------------------------------------------- tokens

    @Test
    @DisplayName("an expired access token is refused")
    void expiredAccessTokensAreRefused() {
        UUID userId = register();
        String expired = io.jsonwebtoken.Jwts.builder()
                .subject(userId.toString())
                .issuedAt(java.util.Date.from(Instant.now().minusSeconds(7200)))
                .expiration(java.util.Date.from(Instant.now().minusSeconds(3600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        authProps.getJwtSecret().getBytes(StandardCharsets.UTF_8)))
                .compact();

        // Short-lived access tokens only limit damage if the expiry is checked.
        assertThatThrownBy(() -> auth.verifyAccessToken(expired))
                .isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("an access token signed with the wrong key is refused")
    void forgedAccessTokensAreRefused() {
        UUID userId = register();
        String forged = io.jsonwebtoken.Jwts.builder()
                .subject(userId.toString())
                .expiration(java.util.Date.from(Instant.now().plusSeconds(3600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "an-attacker-controlled-secret-0123456789".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> auth.verifyAccessToken(forged))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> auth.verifyAccessToken("not-a-token"))
                .isInstanceOf(AuthException.class);
    }

    // ------------------------------------------------------- signed URLs

    @Test
    @DisplayName("a tampered or expired signature does not open a file")
    void signedUrlsAreNotForgeable() throws Exception {
        Learner owner = learnerWithMaterial();
        String url = materials.viewerUrl(owner.userId(), owner.materialId());

        String key = url.substring(url.indexOf("/files/") + 7, url.indexOf('?'));
        long expires = Long.parseLong(param(url, "expires"));
        String signature = param(url, "sig");

        assertThat(storage.verifySignature(decode(key), expires, signature))
                .as("the URL the product just issued must work")
                .isTrue();

        assertThat(storage.verifySignature(decode(key), expires, signature.substring(1) + "A"))
                .as("a forged signature must not open the file")
                .isFalse();
        assertThat(storage.verifySignature(decode(key), expires + 3600, signature))
                .as("extending the expiry invalidates the signature it was signed with")
                .isFalse();
        assertThat(storage.verifySignature("materials/someone-else/secret", expires, signature))
                .as("a signature is bound to one key")
                .isFalse();

        String expired = storage.signedReadUrl(decode(key), Duration.ofSeconds(-30));
        assertThat(storage.verifySignature(decode(key),
                Long.parseLong(param(expired, "expires")), param(expired, "sig")))
                .as("an expired link is not a link")
                .isFalse();
    }

    @Test
    @DisplayName("a storage key cannot escape the storage root")
    void storageKeysCannotTraverse() {
        // Keys are built from ids the server controls today, but the root is the
        // last line of defence and it must hold on its own.
        for (String key : List.of("../../etc/passwd", "materials/../../secrets",
                "..\\..\\windows\\system32", "a/../../../outside")) {
            assertThatThrownBy(() -> storage.exists(key))
                    .as("key %s must not resolve outside the storage root", key)
                    .isInstanceOf(LocalFileSystemStorage.StorageException.class);
        }
    }

    // ---------------------------------------------------------- credentials

    @Test
    @DisplayName("the password is not recoverable from the database")
    void passwordsAreHashed() {
        String password = "correct-horse-battery-staple";
        UUID userId = auth.register("hash-" + UUID.randomUUID() + "@example.com",
                password, "Hashed").userId();

        String stored = jdbc.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = ?", String.class, userId);

        assertThat(stored).doesNotContain(password);
        assertThat(stored)
                .as("Argon2id, not a fast hash: a leaked table must stay expensive to attack")
                .startsWith("$argon2id$");
    }

    @Test
    @DisplayName("a wrong password does not authenticate")
    void wrongPasswordIsRefused() {
        String email = "login-" + UUID.randomUUID() + "@example.com";
        auth.register(email, "the-real-password", "Learner");

        assertThatThrownBy(() -> auth.login(email, "the-real-password-almost"))
                .isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("a refresh token cannot be replayed, and replaying it ends every session")
    void refreshTokensRotateAndCannotBeReplayed() {
        String email = "rotate-" + UUID.randomUUID() + "@example.com";
        AuthService.Tokens first = auth.register(email, "correct-horse-battery", "Learner");

        AuthService.Tokens second = auth.refresh(first.refreshToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());

        // The stolen copy is what an attacker would hold.
        assertThatThrownBy(() -> auth.refresh(first.refreshToken()))
                .isInstanceOfSatisfying(AuthException.class, e ->
                        assertThat(e.code()).isEqualTo("REFRESH_TOKEN_REUSED"));

        // Reuse means one of the two copies is stolen and we cannot tell which,
        // so the safe move is to end both.
        assertThatThrownBy(() -> auth.refresh(second.refreshToken()))
                .isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("logging out revokes the refresh token")
    void logoutRevokes() {
        AuthService.Tokens tokens = auth.register(
                "logout-" + UUID.randomUUID() + "@example.com", "correct-horse-battery", "Learner");

        auth.logout(tokens.refreshToken());

        assertThatThrownBy(() -> auth.refresh(tokens.refreshToken()))
                .isInstanceOf(AuthException.class);
    }

    // -------------------------------------------------------------- uploads

    @Test
    @DisplayName("a suspected exam dump is refused at the door")
    void examDumpsAreRejected() throws Exception {
        UUID userId = register();
        UUID planId = draftPlan(userId);

        assertThatThrownBy(() -> materials.upload(userId, planId,
                "AIF-C01 real exam questions 2026.pdf", "application/pdf", SyntheticDeck.build(2)))
                .isInstanceOfSatisfying(MaterialService.MaterialException.class, e ->
                        assertThat(e.code()).isEqualTo("EXAM_DUMP_REJECTED"));

        assertThat(materials.listForUser(userId))
                .as("a rejected upload must not be stored at all")
                .isEmpty();
    }

    @Test
    @DisplayName("the declared content type is not believed")
    void mimeTypeIsSniffedNotTrusted() {
        UUID userId = register();
        UUID planId = draftPlan(userId);
        byte[] notAPdf = "#!/bin/sh\nrm -rf /\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> materials.upload(userId, planId,
                "course.pdf", "application/pdf", notAPdf))
                .isInstanceOfSatisfying(MaterialService.MaterialException.class, e ->
                        assertThat(e.code()).isEqualTo("UNSUPPORTED_FORMAT"));
    }

    @Test
    @DisplayName("an oversized upload is refused before it is stored")
    void oversizedUploadIsRejected() {
        UUID userId = register();
        UUID planId = draftPlan(userId);
        byte[] tooBig = new byte[(int) storageProps.getMaxUploadBytes() + 1];
        tooBig[0] = '%';
        tooBig[1] = 'P';
        tooBig[2] = 'D';
        tooBig[3] = 'F';

        assertThatThrownBy(() -> materials.upload(userId, planId, "huge.pdf",
                "application/pdf", tooBig))
                .isInstanceOfSatisfying(MaterialService.MaterialException.class, e ->
                        assertThat(e.code()).isEqualTo("FILE_TOO_LARGE"));

        // The cap has to be enforced server side; a client-side limit is a hint.
        assertThat(materials.listForUser(userId)).isEmpty();
    }

    @Test
    @DisplayName("an empty upload is refused")
    void emptyUploadIsRejected() {
        UUID userId = register();
        UUID planId = draftPlan(userId);

        assertThatThrownBy(() -> materials.upload(userId, planId,
                "course.pdf", "application/pdf", new byte[0]))
                .isInstanceOf(MaterialService.MaterialException.class);
    }

    // -------------------------------------------------------------- helpers

    private record Learner(UUID userId, UUID planId, UUID materialId) {
    }

    private Learner learnerWithMaterial() throws Exception {
        UUID userId = register();
        UUID planId = draftPlan(userId);
        MaterialService.UploadResult upload = materials.upload(
                userId, planId, "course.pdf", "application/pdf", SyntheticDeck.build(2));
        return new Learner(userId, planId, upload.materialId());
    }

    private UUID register() {
        return auth.register("sec-" + UUID.randomUUID() + "@example.com",
                "correct-horse-battery", "Learner").userId();
    }

    private UUID draftPlan(UUID userId) {
        Map<String, Integer> capacity = new LinkedHashMap<>();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            capacity.put(day.name(), 120);
        }
        return plans.createDraft(userId, AIF_C01, LocalDate.now().plusDays(45),
                capacity, List.of(), null);
    }

    private static String param(String url, String name) {
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return parts[1];
            }
        }
        throw new AssertionError("no " + name + " in " + url);
    }

    private static String decode(String encoded) {
        return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
