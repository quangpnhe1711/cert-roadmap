package com.certcopilot.archfixture.forbidden.planroute;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Correction P3 fixture: a plan-scoped route that trusts the id in the URL.
 *
 * <p>This is the shape of a real defect found in this codebase: the pack endpoint
 * took a planId, never checked who was asking, and served content generated from
 * the owner's private course material to anyone who knew the id. It looked
 * exactly like the endpoints beside it, which is why a build-time rule and not a
 * review checklist is the right guard. Rule R8 must DETECT this class.
 */
public class LeakyPlanRouteController {

    @GetMapping("/plans/{planId}/something")
    public String read(@PathVariable UUID planId) {
        return "here is the owner's content: " + planId;
    }
}
