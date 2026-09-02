package com.certcopilot.platform.ai;

/**
 * The only seam through which a language model is reached.
 *
 * <p>ArchUnit rule R6 forbids any class outside this package from importing a
 * provider SDK, so this interface is the enforced boundary rather than a
 * convention.
 */
public interface LlmPort {

    /**
     * Model ids produced by a non-production adapter begin with this.
     *
     * <p>A contract rather than a naming coincidence: it is what lets the product
     * refuse to present a coverage percentage, or any other measured claim, that
     * was actually computed from fixture output.
     */
    String SYNTHETIC_MODEL_PREFIX = "fake-";

    Response call(Request request);

    /**
     * Who would answer a call at this tier, as a stable string.
     *
     * <p>Part of the artifact cache key, and the reason it exists: without it a
     * lesson written by the deterministic fake is indistinguishable, to the
     * cache, from one written by a real model, and switching {@code AI_ADAPTER}
     * would serve fixture prose under a provider's name. Correction A2 always
     * said the generation half of the key covers the model; this is what makes
     * that true rather than a comment.
     *
     * <p>Names the configured model rather than the one the provider reports
     * back, because the key has to be computable before the call.
     */
    String generationIdentity(ModelTier tier);

    record Request(
            String operationId,
            ModelTier tier,
            String prompt,
            String promptVersion,
            int maxOutputTokens,
            int attemptNo,
            String validationFeedback,
            /**
             * The same inputs the prompt was rendered from, as JSON.
             *
             * <p>A real provider adapter ignores this and sends {@code prompt}.
             * The deterministic fake reads it so that development and CI exercise
             * the full pipeline - validators, retries, caching, cost - against
             * realistic output without a network call or an API key.
             */
            String structuredInput) {
    }

    record Response(
            String rawOutput,
            String model,
            int tokensIn,
            int tokensOut,
            long latencyMs) {
    }
}
