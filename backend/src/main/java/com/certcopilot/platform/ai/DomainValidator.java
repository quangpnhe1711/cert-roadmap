package com.certcopilot.platform.ai;

/**
 * Domain-specific correctness check applied after schema validation.
 *
 * <p>This is where operation-specific rules live: page ranges inside the unit,
 * MUST_KNOW coverage, n-gram overlap against the source, a quiz question having
 * a resolvable sourceSpan. Failing here consumes an attempt and is recorded in
 * the ledger, because the provider call already happened.
 */
public interface DomainValidator {

    /**
     * @param rawOutput       exactly what the provider returned
     * @param structuredInput the same inputs the prompt was built from, as JSON,
     *                        so a validator can check the output <em>against</em>
     *                        what was asked rather than only for self-consistency.
     *                        Checking a quiz sourceSpan actually resolves in the
     *                        source text is only possible with this.
     */
    ValidationResult validate(String rawOutput, String structuredInput);

    record ValidationResult(boolean valid, String failureReason) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
