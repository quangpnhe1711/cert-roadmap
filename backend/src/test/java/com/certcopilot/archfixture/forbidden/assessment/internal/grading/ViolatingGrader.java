package com.certcopilot.archfixture.forbidden.assessment.internal.grading;

import com.certcopilot.platform.ai.AiGateway;

/**
 * Correction P3 fixture: grading that asks a model whether an answer is right.
 * Rule R2 must DETECT this class.
 */
public class ViolatingGrader {

    private final AiGateway gateway;

    public ViolatingGrader(AiGateway gateway) {
        this.gateway = gateway;
    }

    public String grade() {
        return "would ask " + gateway.getClass().getSimpleName() + " to judge the answer";
    }
}
