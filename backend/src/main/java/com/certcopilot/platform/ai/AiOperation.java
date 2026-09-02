package com.certcopilot.platform.ai;

/**
 * A declared AI capability. Adding an AI feature means adding one of these plus
 * a prompt file plus a validator - never new call, retry, cost or cache code.
 *
 * <p>Because every operation flows through {@link AiGateway}, cost tracking is
 * automatic and cannot be forgotten.
 *
 * @param id                   stable identifier, also used in the ledger
 * @param promptVersion        part of the artifact key (A2)
 * @param outputSchemaVersion  part of the artifact key (A2)
 * @param modelConfigVersion   temperature, max tokens and so on, versioned as a unit (A2)
 * @param tier                 logical model tier
 * @param maxOutputTokens      hard ceiling passed to the provider
 * @param estimatedCostCents   used for the budget reservation before the call (A3)
 * @param maxAttempts          bounded retry
 * @param validator            domain correctness check run after schema validation
 * @param essential            when false, this operation is refused first at the hard budget cap
 */
public record AiOperation(
        String id,
        String promptVersion,
        String outputSchemaVersion,
        String modelConfigVersion,
        ModelTier tier,
        int maxOutputTokens,
        int estimatedCostCents,
        int maxAttempts,
        DomainValidator validator,
        boolean essential) {
}
