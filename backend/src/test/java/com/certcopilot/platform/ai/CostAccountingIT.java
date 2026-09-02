package com.certcopilot.platform.ai;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.domain.identity.AuthService;
import com.certcopilot.domain.planning.PlanService;
import com.certcopilot.shared.Json;
import com.certcopilot.support.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Correction A3, the half that concurrency tests do not reach: <em>every</em>
 * attempt is accounted for, including the ones nobody wanted.
 *
 * <p>Spending is not a business outcome that can be undone by a business
 * rollback. The tokens left the building. If the caller's transaction later
 * fails - a constraint, a bad payload, any exception after the provider
 * responded - the ledger row and the settled budget have to survive it, or the
 * plan reports less spend than actually happened and the cap stops meaning
 * anything.
 */
class CostAccountingIT extends PostgresSupport {

    private static final UUID AIF_C01 = UUID.fromString("a1f00000-0000-4000-8000-000000000002");

    @Autowired private AiGateway gateway;
    @Autowired private AiCallLedger ledger;
    @Autowired private BudgetService budget;
    @Autowired private AuthService auth;
    @Autowired private PlanService plans;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    private UUID planId;

    @BeforeEach
    void setUp() {
        requireDatabase();
        UUID userId = auth.register("cost-" + UUID.randomUUID() + "@example.com",
                "correct-horse-battery", "Cost").userId();
        Map<String, Integer> capacity = new LinkedHashMap<>();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            capacity.put(day.name(), 120);
        }
        planId = plans.createDraft(userId, AIF_C01, LocalDate.now().plusDays(30),
                capacity, List.of(), null);
    }

    @Test
    @DisplayName("output rejected by the validator is still billed")
    void rejectedOutputIsStillBilled() {
        // The adapter answers on every attempt and the validator rejects every
        // answer. Real tokens spent, no usable artifact produced.
        AiResult result = execute(input("failalways"));

        assertThat(result).isInstanceOf(AiResult.Failed.class);
        assertThat(ledger.totalCostCents(planId))
                .as("a rejected attempt consumed provider tokens and must appear in the ledger")
                .isPositive();
        assertThat(attemptCount())
                .as("every attempt gets its own ledger row, not just the last one")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("the ledger survives a rollback of the transaction that spent the money")
    void ledgerSurvivesCallerRollback() {
        assertThatThrownBy(() -> transactions.execute(status -> {
            execute(input("failalways"));
            // Anything at all going wrong after the provider replied: a constraint,
            // a malformed payload, a bug three lines later.
            throw new IllegalStateException("business failure after the money was spent");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(ledger.totalCostCents(planId))
                .as("spend is not undone by a rollback; the tokens are gone either way")
                .isPositive();
    }

    @Test
    @DisplayName("settled budget survives a rollback of the transaction that spent it")
    void settlementSurvivesCallerRollback() {
        assertThatThrownBy(() -> transactions.execute(status -> {
            execute(input("failalways"));
            throw new IllegalStateException("business failure after the money was spent");
        })).isInstanceOf(IllegalStateException.class);

        BudgetService.Snapshot snapshot = budget.snapshot(planId);
        assertThat(snapshot.settledCents())
                .as("un-settling real spend lets the same budget be spent twice")
                .isPositive();
        assertThat(snapshot.reservedCents())
                .as("reservations are settled, not left dangling")
                .isZero();
    }

    @Test
    @DisplayName("a cache hit costs nothing and is recorded as a cache hit")
    void cacheHitsAreFree() {
        String payload = input(null);
        ArtifactKey key = cacheKey();

        AiResult first = gateway.execute(AiOperations.PACK_GENERATE, planId, key, "prompt", payload);
        // If the fake output cannot satisfy the validator here, the premise of the
        // test is gone and asserting on cache behaviour would prove nothing.
        assertThat(first).isInstanceOf(AiResult.Ok.class);
        int afterFirst = ledger.totalCostCents(planId);

        AiResult second = gateway.execute(AiOperations.PACK_GENERATE, planId, key, "prompt", payload);

        assertThat(second).isInstanceOfSatisfying(AiResult.Ok.class, ok -> {
            assertThat(ok.cacheHit()).isTrue();
            assertThat(ok.costCents()).isZero();
        });
        assertThat(ledger.totalCostCents(planId))
                .as("a cache hit must not add provider cost")
                .isEqualTo(afterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_call_ledger WHERE plan_id = ? AND outcome = 'CACHE_HIT'",
                Integer.class, planId))
                .as("the saving is still recorded, so cache effectiveness is measurable")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an invalidated artifact is regenerated rather than served from cache")
    void invalidatedArtifactsAreNotServed() {
        String payload = input(null);
        ArtifactKey key = cacheKey();
        AiResult first = gateway.execute(AiOperations.PACK_GENERATE, planId, key, "prompt", payload);
        assertThat(first).isInstanceOf(AiResult.Ok.class);

        // Invalidate by the key the gateway actually stored under. The caller's
        // key is not the effective one: the gateway folds in which model would
        // answer, so an artifact from the fake adapter is never reachable from a
        // deployment running a real one.
        String storedKey = ((AiResult.Ok) first).artifactKey();
        assertThat(jdbc.update("UPDATE generated_artifact SET cache_status = 'INVALIDATED', "
                + " invalidated_reason = 'flagged by a learner' WHERE artifact_key = ?", storedKey))
                .as("the artifact must be reachable by the key the gateway reported")
                .isEqualTo(1);

        AiResult after = gateway.execute(AiOperations.PACK_GENERATE, planId, key, "prompt", payload);

        assertThat(after).isInstanceOfSatisfying(AiResult.Ok.class, ok -> assertThat(ok.cacheHit())
                .as("content withdrawn for being wrong must never come back from the cache")
                .isFalse());
    }

    // ----------------------------------------------------------------- helpers

    /**
     * The structured input a real caller sends. {@code marker} drives the fake
     * adapter: "failalways" makes every attempt produce output the validator
     * rejects, which is how a billable-but-useless attempt is reproduced offline.
     */
    private String input(String marker) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("title", "Bedrock probe");
        node.put("pageStart", 1);
        node.put("pageEnd", 2);
        node.put("sourceText", "Amazon Bedrock cung cấp foundation model qua API quản trị.");
        node.put("mustKnow", List.of());
        node.put("taskStatements", List.of());
        node.put("hasSignificantVisual", false);
        node.put("testMarker", marker == null ? "" : marker);
        return Json.write(node);
    }

    private AiResult execute(String structuredInput) {
        return gateway.execute(AiOperations.PACK_GENERATE, planId, uniqueKey(), "prompt", structuredInput);
    }

    /** A fresh identity per call, so these tests exercise generation rather than the cache. */
    private ArtifactKey uniqueKey() {
        return ArtifactKey.of("LEARNING_PACK")
                .semantic("probe", UUID.randomUUID().toString())
                .operation(operation())
                .build();
    }

    private ArtifactKey cacheKey() {
        return ArtifactKey.of("LEARNING_PACK")
                .semantic("probe", "stable-" + planId)
                .operation(operation())
                .build();
    }

    private AiOperation operation() {
        return registry.require(AiOperations.PACK_GENERATE);
    }

    @Autowired private AiOperationRegistry registry;

    private int attemptCount() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ai_call_ledger WHERE plan_id = ? AND NOT cache_hit",
                Integer.class, planId);
        return count == null ? 0 : count;
    }
}
