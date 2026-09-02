package com.certcopilot.platform.ai;

/**
 * A provider failure, classified by whether trying again could possibly help.
 *
 * <p>Part of the {@link LlmPort} contract rather than of any one adapter: the
 * gateway must be able to tell "the model produced something wrong" from "the
 * model was never reached", because only the first is worth a fresh prompt.
 *
 * <p>The split of responsibility is deliberate:
 *
 * <ul>
 *   <li>An adapter retries <em>transport</em> failures itself - a 429, a 503, a
 *       socket timeout - because no tokens were spent and nothing about the
 *       request needs to change. When it gives up it reports {@link Kind#PERMANENT},
 *       so the gateway does not immediately repeat a wait the adapter has already
 *       served. Recovering from a provider outage is the job queue's job, and it
 *       already has backoff.</li>
 *   <li>The gateway retries <em>content</em> failures - parse, schema, domain -
 *       because those are the ones a corrected prompt can fix.</li>
 * </ul>
 *
 * <p>An authentication or malformed-request failure is {@link Kind#PERMANENT} on
 * the first response. Retrying a bad API key three times only spends three times
 * as long failing.
 */
public class LlmException extends RuntimeException {

    public enum Kind {
        /** Transient; the same request may succeed later. */
        RETRYABLE,
        /** Retrying will not help, or the adapter has already exhausted its retries. */
        PERMANENT,
        /** The calling thread was interrupted. Not a failure of the provider. */
        CANCELLED
    }

    private final Kind kind;
    private final Integer httpStatus;
    private final String providerErrorType;

    public LlmException(Kind kind, Integer httpStatus, String providerErrorType,
                        String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.providerErrorType = providerErrorType;
    }

    public static LlmException retryable(Integer status, String type, String message) {
        return new LlmException(Kind.RETRYABLE, status, type, message, null);
    }

    public static LlmException permanent(Integer status, String type, String message) {
        return new LlmException(Kind.PERMANENT, status, type, message, null);
    }

    public static LlmException cancelled(String message) {
        return new LlmException(Kind.CANCELLED, null, null, message, null);
    }

    /** A copy of this failure that the gateway must not retry. */
    public LlmException asPermanent(String message) {
        return new LlmException(Kind.PERMANENT, httpStatus, providerErrorType, message, this);
    }

    public Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return kind == Kind.RETRYABLE;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String providerErrorType() {
        return providerErrorType;
    }
}
