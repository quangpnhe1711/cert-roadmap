package com.certcopilot.platform.ai;

import java.util.UUID;

/**
 * Outcome of an {@link AiGateway} execution.
 *
 * <p>{@code Degraded} is a first-class result, not an error: the fallback
 * strategy produced something usable but reduced. The caller decides whether to
 * tell the user.
 */
public sealed interface AiResult {

    record Ok(UUID artifactId, String payload, String artifactKey,
              boolean cacheHit, int costCents, int attempts) implements AiResult {
    }

    record Degraded(UUID artifactId, String payload, String artifactKey,
                    String reason, int costCents, int attempts) implements AiResult {
    }

    record Failed(String reason, AiOutcome lastOutcome,
                  int costCents, int attempts) implements AiResult {
    }
}
