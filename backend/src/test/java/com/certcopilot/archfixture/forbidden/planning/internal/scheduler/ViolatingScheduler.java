package com.certcopilot.archfixture.forbidden.planning.internal.scheduler;

import com.certcopilot.platform.ai.AiGateway;

/**
 * Correction P3 fixture: a scheduler that breaks rule R1 by reaching into the AI
 * platform. Rule R1 must DETECT this class.
 *
 * <p>This is the mistake the rule exists to prevent: letting a model influence a
 * hard scheduling constraint. It is compiled on purpose so the rule is proven to
 * have teeth rather than merely being green.
 */
public class ViolatingScheduler {

    private final AiGateway gateway;

    public ViolatingScheduler(AiGateway gateway) {
        this.gateway = gateway;
    }

    public String decideSchedule() {
        return "would ask " + gateway.getClass().getSimpleName() + " to pick the dates";
    }
}
