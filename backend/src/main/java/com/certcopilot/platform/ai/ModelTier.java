package com.certcopilot.platform.ai;

/**
 * Logical model tier. Domain code never names a provider or a model id; the
 * mapping from tier to concrete model lives in configuration so switching
 * provider is a config change (System Design, Stack table).
 */
public enum ModelTier {
    /** Cheap and fast: structure extraction, keyword filtering, short explanations. */
    FAST,
    /** Quality critical and user facing: lessons, quiz, exam mapping. */
    QUALITY
}
