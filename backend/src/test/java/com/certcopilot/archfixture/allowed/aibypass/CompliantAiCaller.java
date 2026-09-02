package com.certcopilot.archfixture.allowed.aibypass;

import java.util.UUID;

import com.certcopilot.platform.ai.AiGateway;
import com.certcopilot.platform.ai.ArtifactKey;

/**
 * Correction P3 fixture: a feature that reaches a model the only sanctioned way.
 * Rule R5 must ACCEPT this class.
 */
public class CompliantAiCaller {

    private final AiGateway gateway;

    public CompliantAiCaller(AiGateway gateway) {
        this.gateway = gateway;
    }

    public Object generate(UUID planId, ArtifactKey key, String prompt) {
        return gateway.execute("learning.pack.generate", planId, key, prompt);
    }
}
