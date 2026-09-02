package com.certcopilot.platform.ai;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single seam through which every language-model call passes.
 *
 * <p>Order of operations is correction A3, and the order matters:
 *
 * <pre>
 *   1  compute artifact key, look up cache (VALID only)   -> hit: no provider call, cost 0
 *   2  reserve budget atomically                          -> denied: stop, nothing spent
 *   3  call the provider
 *   4  RECORD ACTUAL COST for this attempt, always        -> even if 5/6/7 below reject it
 *   5  parse
 *   6  schema validation
 *   7  domain validation
 *   8  bounded retry loops back to 2 with a fresh reservation and a fresh ledger row
 *   9  cache only a valid artifact
 * </pre>
 *
 * <p>Recording cost after validation - as an earlier draft of the design did -
 * makes every retry invisible to the budget, and retries are most frequent
 * exactly while prompt quality is still poor.
 *
 * <p>Architecture rules R1-R3 forbid the scheduler, grading and mastery packages
 * from depending on this package, so "the model never decides schedule or score"
 * is enforced by the build rather than by convention.
 */
@Component
public class AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

    private final LlmPort llm;
    private final AiOperationRegistry registry;
    private final BudgetService budget;
    private final AiCallLedger ledger;
    private final ArtifactStore artifacts;
    private final AiProperties props;
    private final ObjectMapper objectMapper;

    public AiGateway(LlmPort llm,
                     AiOperationRegistry registry,
                     BudgetService budget,
                     AiCallLedger ledger,
                     ArtifactStore artifacts,
                     AiProperties props,
                     ObjectMapper objectMapper) {
        this.llm = llm;
        this.registry = registry;
        this.budget = budget;
        this.ledger = ledger;
        this.artifacts = artifacts;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes a declared operation.
     *
     * @param operationId id registered in {@link AiOperationRegistry}
     * @param planId      budget and ledger scope
     * @param rawKey      artifact identity as the caller knows it (A2); the
     *                    generating model is folded in here, not by the caller
     * @param prompt      rendered prompt text
     */
    public AiResult execute(String operationId, UUID planId, ArtifactKey rawKey, String prompt) {
        return execute(operationId, planId, rawKey, prompt, null);
    }

    public AiResult execute(String operationId, UUID planId, ArtifactKey rawKey,
                            String prompt, String structuredInput) {
        AiOperation op = registry.require(operationId);

        // ---- 0. fold in who would answer -----------------------------------
        // Callers describe what the artifact is about; only here is it known what
        // would produce it. Without this the cache cannot tell a lesson written
        // by the deterministic fake from one written by a real model, and
        // switching AI_ADAPTER would serve fixture prose under a provider's name.
        ArtifactKey key = rawKey.withGeneration("generator", llm.generationIdentity(op.tier()));

        // ---- 1. cache -------------------------------------------------------
        Optional<ArtifactStore.Stored> cached = artifacts.findValid(key);
        if (cached.isPresent()) {
            ArtifactStore.Stored hit = cached.get();
            ledger.record(planId, op.id(), 0, "-", 0, 0, 0, 0,
                    AiOutcome.CACHE_HIT, key.value(), true, null);
            log.debug("cache hit for {} key={}", op.id(), key);
            return new AiResult.Ok(hit.id(), hit.payload(), hit.artifactKey(), true, 0, 0);
        }

        int spentCents = 0;
        String lastFailure = "not attempted";
        AiOutcome lastOutcome = AiOutcome.PROVIDER_ERROR;
        String feedback = null;

        for (int attempt = 1; attempt <= op.maxAttempts(); attempt++) {

            // ---- 2. atomic budget reservation -------------------------------
            UUID reservationId = budget.reserve(planId, op.id(), op.estimatedCostCents(),
                    Duration.ofMillis(props.getReservationTtlMs()));
            if (reservationId == null) {
                ledger.record(planId, op.id(), attempt, "-", 0, 0, 0, 0,
                        AiOutcome.BUDGET_DENIED, key.value(), false, null);
                return new AiResult.Failed(
                        "AI budget exhausted for this plan", AiOutcome.BUDGET_DENIED,
                        spentCents, attempt - 1);
            }

            LlmPort.Response response = null;
            int attemptCost = 0;
            AiOutcome outcome;
            String failureReason = null;
            String payload = null;
            boolean unrecoverable = false;

            try {
                // ---- 3. provider call ---------------------------------------
                response = llm.call(new LlmPort.Request(
                        op.id(), op.tier(), prompt, op.promptVersion(),
                        op.maxOutputTokens(), attempt, feedback, structuredInput));
                attemptCost = props.costCents(response.model(), op.tier(),
                        response.tokensIn(), response.tokensOut());

                // ---- 5. parse + 6. schema + 7. domain ------------------------
                JsonNode parsed;
                try {
                    parsed = objectMapper.readTree(response.rawOutput());
                } catch (Exception parseError) {
                    outcome = AiOutcome.PARSE_FAIL;
                    failureReason = "output was not valid JSON: " + parseError.getMessage();
                    parsed = null;
                }

                if (failureReason == null) {
                    if (parsed == null || !parsed.isObject()) {
                        outcome = AiOutcome.SCHEMA_FAIL;
                        failureReason = "output was not a JSON object";
                    } else {
                        DomainValidator.ValidationResult validation =
                                op.validator().validate(response.rawOutput(), structuredInput);
                        if (validation.valid()) {
                            outcome = AiOutcome.SUCCESS;
                            payload = response.rawOutput();
                        } else {
                            outcome = AiOutcome.DOMAIN_FAIL;
                            failureReason = validation.failureReason();
                        }
                    }
                } else {
                    outcome = AiOutcome.PARSE_FAIL;
                }
            } catch (LlmException providerError) {
                outcome = AiOutcome.PROVIDER_ERROR;
                failureReason = providerError.getMessage();
                unrecoverable = !providerError.retryable();
            } catch (RuntimeException providerError) {
                outcome = AiOutcome.PROVIDER_ERROR;
                failureReason = providerError.toString();
            }

            // ---- 4. ALWAYS record the attempt, then settle the reservation ---
            ledger.record(planId, op.id(), attempt,
                    response == null ? "-" : response.model(),
                    response == null ? 0 : response.tokensIn(),
                    response == null ? 0 : response.tokensOut(),
                    attemptCost,
                    response == null ? 0 : response.latencyMs(),
                    outcome, key.value(), false, reservationId);
            budget.settle(reservationId, planId, op.estimatedCostCents(), attemptCost);
            spentCents += attemptCost;

            if (outcome == AiOutcome.SUCCESS) {
                // ---- 9. cache only a valid artifact -------------------------
                UUID artifactId = artifacts.store(key, planId, payload, op,
                        response.model(), response.tokensIn(), response.tokensOut(), spentCents);
                return new AiResult.Ok(artifactId, payload, key.value(), false, spentCents, attempt);
            }

            lastOutcome = outcome;
            lastFailure = failureReason;
            // Feed the rejection back only when a different answer is possible.
            // A bad API key is not something a corrected prompt can fix.
            feedback = unrecoverable ? null : failureReason;
            log.warn("AI operation {} attempt {}/{} rejected ({}): {}",
                    op.id(), attempt, op.maxAttempts(), outcome, failureReason);

            if (unrecoverable) {
                // The adapter has already exhausted whatever waiting was worth
                // doing. Spending two more reservations to fail identically is
                // just a slower failure, and the job queue retries the job.
                return new AiResult.Failed(failureReason, outcome, spentCents, attempt);
            }
        }

        // ---- 8. attempts exhausted -----------------------------------------
        return new AiResult.Failed(lastFailure, lastOutcome, spentCents, op.maxAttempts());
    }
}
