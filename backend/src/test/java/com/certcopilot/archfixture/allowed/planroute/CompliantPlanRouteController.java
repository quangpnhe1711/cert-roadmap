package com.certcopilot.archfixture.allowed.planroute;

import java.util.UUID;

import com.certcopilot.domain.planning.PlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Correction P3 fixture: a plan-scoped route that checks ownership first.
 * Rule R8 must ACCEPT this class.
 */
public class CompliantPlanRouteController {

    private final PlanService plans;

    public CompliantPlanRouteController(PlanService plans) {
        this.plans = plans;
    }

    @GetMapping("/plans/{planId}/something")
    public String read(@PathVariable UUID planId) {
        plans.requireOwned(UUID.randomUUID(), planId);
        return "ok";
    }
}
