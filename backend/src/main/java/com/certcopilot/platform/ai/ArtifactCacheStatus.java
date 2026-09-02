package com.certcopilot.platform.ai;

/**
 * Correction A2: artifacts are never overwritten.
 *
 * <p>A superseded artifact is still what a learner actually studied, so it is
 * retained. An invalidated one is blocked from reuse - this is what stops the
 * cache turning one bad result into a permanent one (risk AR-8).
 */
public enum ArtifactCacheStatus {
    VALID,
    SUPERSEDED,
    INVALIDATED
}
