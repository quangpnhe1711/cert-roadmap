package com.certcopilot.platform.ai;

/**
 * Outcome of a single provider attempt, written to the ledger for every call.
 *
 * <p>Correction A3: a parse or validation failure has already consumed provider
 * tokens, so it is a billable outcome, not a silent discard.
 */
public enum AiOutcome {
    SUCCESS,
    PARSE_FAIL,
    SCHEMA_FAIL,
    DOMAIN_FAIL,
    PROVIDER_ERROR,
    CACHE_HIT,
    BUDGET_DENIED
}
