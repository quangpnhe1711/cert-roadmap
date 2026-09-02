package com.certcopilot.platform.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

/**
 * Correction A3: one row per provider attempt, written before parsing and
 * validation decide whether the output is usable.
 *
 * <p>Recording only successful calls makes retries invisible to the budget, and
 * retries are most frequent exactly when prompt quality is still poor - the
 * moment the number matters most. The cost estimate in the System Design carries
 * a "retry and rejected calls" line for this reason.
 */
@Component
public class AiCallLedger {

    private final JdbcTemplate jdbc;

    public AiCallLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes one attempt to the ledger, in its own transaction.
     *
     * <p>{@code REQUIRES_NEW} is the whole point. Correction A3 says every
     * attempt is accounted for, and an audit record that a later business
     * rollback can erase does not account for anything: the tokens were spent
     * whatever happened next, and a plan that under-reports its own spend lets
     * the same budget be spent twice.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID planId,
                       String operationId,
                       int attemptNo,
                       String model,
                       int tokensIn,
                       int tokensOut,
                       int costCents,
                       long latencyMs,
                       AiOutcome outcome,
                       String artifactKey,
                       boolean cacheHit,
                       UUID reservationId) {
        jdbc.update(
                "INSERT INTO ai_call_ledger "
                        + "(id, plan_id, operation_id, attempt_no, model, tokens_in, tokens_out, "
                        + " cost_cents, latency_ms, outcome, artifact_key, cache_hit, reservation_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), planId, operationId, attemptNo, model, tokensIn, tokensOut,
                costCents, (int) latencyMs, outcome.name(), artifactKey, cacheHit, reservationId);
    }

    /** Total spend for a plan, including attempts that were rejected. */
    public int totalCostCents(UUID planId) {
        Integer total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cost_cents), 0) FROM ai_call_ledger WHERE plan_id = ?",
                Integer.class, planId);
        return total == null ? 0 : total;
    }

    /** Per-operation breakdown, used by the admin cost view. */
    public List<Map<String, Object>> costByOperation(UUID planId) {
        return jdbc.queryForList(
                "SELECT operation_id, "
                        + "       COUNT(*) AS calls, "
                        + "       COUNT(*) FILTER (WHERE outcome = 'SUCCESS') AS successes, "
                        + "       COUNT(*) FILTER (WHERE cache_hit) AS cache_hits, "
                        + "       COALESCE(SUM(cost_cents), 0) AS cost_cents "
                        + "  FROM ai_call_ledger WHERE plan_id = ? "
                        + " GROUP BY operation_id ORDER BY operation_id",
                planId);
    }

    /**
     * What this plan's generated content actually cost, in one row.
     *
     * <p>Deliberately measured from the ledger rather than from today's
     * configuration: a plan generated in development still reports zero real
     * tokens after the server is restarted against a provider. That is the whole
     * difference between "this deployment is on Gemini" and "this lesson came
     * from Gemini", and only the second is worth anything to a learner.
     *
     * <p>{@code pricingConfigured} is what stops an estimate being read as a
     * measurement: with no published price for the model in {@code AiProperties},
     * the cents below come from a tier estimate and say so.
     */
    public Usage usageFor(UUID planId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COALESCE(SUM(tokens_in), 0) AS tokens_in, "
                        + "       COALESCE(SUM(tokens_out), 0) AS tokens_out, "
                        + "       COALESCE(SUM(cost_cents), 0) AS cost_cents, "
                        + "       COUNT(*) FILTER (WHERE NOT cache_hit AND model <> '-') AS provider_calls, "
                        + "       COUNT(*) FILTER (WHERE cache_hit) AS cache_hits, "
                        + "       COUNT(*) FILTER (WHERE outcome <> 'SUCCESS' AND outcome <> 'CACHE_HIT') "
                        + "             AS rejected_attempts "
                        + "  FROM ai_call_ledger WHERE plan_id = ?", planId);

        List<String> models = jdbc.queryForList(
                "SELECT DISTINCT model FROM ai_call_ledger "
                        + " WHERE plan_id = ? AND model <> '-' ORDER BY model",
                String.class, planId);

        return new Usage(
                ((Number) row.get("tokens_in")).intValue(),
                ((Number) row.get("tokens_out")).intValue(),
                ((Number) row.get("cost_cents")).intValue(),
                ((Number) row.get("provider_calls")).intValue(),
                ((Number) row.get("cache_hits")).intValue(),
                ((Number) row.get("rejected_attempts")).intValue(),
                models);
    }

    /**
     * @param providerCalls    attempts that actually reached a provider
     * @param cacheHits        attempts served from the artifact cache, costing nothing
     * @param rejectedAttempts attempts that were paid for and thrown away
     * @param models           every model id that served this plan; never a credential
     */
    public record Usage(int tokensIn, int tokensOut, int costCents, int providerCalls,
                        int cacheHits, int rejectedAttempts, List<String> models) {
    }

    public List<Map<String, Object>> attemptsFor(UUID planId) {
        return jdbc.queryForList(
                "SELECT operation_id, attempt_no, outcome, tokens_in, tokens_out, "
                        + "       cost_cents, cache_hit, created_at "
                        + "  FROM ai_call_ledger WHERE plan_id = ? ORDER BY created_at",
                planId);
    }
}
