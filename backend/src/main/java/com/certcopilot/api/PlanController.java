package com.certcopilot.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.api.security.CurrentUser;
import com.certcopilot.domain.assessment.WeakTopicDetector;
import com.certcopilot.domain.mapping.MappingService;
import com.certcopilot.platform.ai.AiCallLedger;
import com.certcopilot.platform.ai.AiProperties;
import com.certcopilot.platform.ai.AiProvenance;
import com.certcopilot.domain.planning.AnalysisProgressService;
import com.certcopilot.domain.planning.CapacityCalculator;
import com.certcopilot.domain.planning.DayCompletionService;
import com.certcopilot.domain.planning.PlanService;
import com.certcopilot.domain.planning.SchedulingService;
import com.certcopilot.domain.planning.TodayService;
import com.certcopilot.domain.planning.internal.scheduler.SchedulingResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plan lifecycle and the composed read models.
 *
 * <p>Writes are resource-oriented; reads that a screen needs whole
 * ({@code /today}, {@code /progress}) are composed server-side.
 */
@RestController
@RequestMapping("/api/v1")
public class PlanController {

    private final PlanService plans;
    private final SchedulingService scheduling;
    private final TodayService today;
    private final DayCompletionService dayCompletion;
    private final WeakTopicDetector weakTopics;
    private final MappingService mappings;
    private final AiProvenance provenance;
    private final AnalysisProgressService analysisProgress;
    private final AiCallLedger ledger;
    private final AiProperties aiProps;
    private final JdbcTemplate jdbc;

    public PlanController(PlanService plans, SchedulingService scheduling, TodayService today,
                          DayCompletionService dayCompletion, WeakTopicDetector weakTopics,
                          MappingService mappings, AiProvenance provenance,
                          AnalysisProgressService analysisProgress, AiCallLedger ledger,
                          AiProperties aiProps, JdbcTemplate jdbc) {
        this.plans = plans;
        this.scheduling = scheduling;
        this.today = today;
        this.dayCompletion = dayCompletion;
        this.weakTopics = weakTopics;
        this.mappings = mappings;
        this.provenance = provenance;
        this.analysisProgress = analysisProgress;
        this.ledger = ledger;
        this.aiProps = aiProps;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ onboarding

    /** Capacity preview before a plan exists, so the first answer arrives instantly. */
    @PostMapping("/plans/capacity-preview")
    public CapacityCalculator.Result capacityPreview(@RequestBody @Valid CapacityPreviewRequest request) {
        return plans.previewCapacity(request.certificationVersionId(), request.examDate(),
                request.weeklyCapacity(), request.blockedDates());
    }

    @PostMapping("/plans")
    public ResponseEntity<Map<String, Object>> createPlan(@RequestBody @Valid CreatePlanRequest request) {
        UUID planId = plans.createDraft(CurrentUser.id(), request.certificationVersionId(),
                request.examDate(), request.weeklyCapacity(), request.blockedDates(),
                request.startDate());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("planId", planId, "status", "DRAFT"));
    }

    /**
     * Every plan the learner has, for the plan list screen.
     *
     * <p>Separate from {@code /plans/current} rather than a filter on it: the two
     * answer different questions - "what am I doing now" drives routing on every
     * page load, "what have I done" is one screen - and merging them would make
     * the hot path pay for the cold one.
     */
    @GetMapping("/plans")
    public List<PlanService.PlanSummary> plans() {
        return plans.listPlans(CurrentUser.id());
    }

    /**
     * Puts a plan away. Nothing is deleted, and the one-active-plan slot is freed
     * so the learner can start the next certification.
     */
    @PostMapping("/plans/{planId}/archive")
    public ResponseEntity<Void> archive(@PathVariable UUID planId) {
        plans.archive(CurrentUser.id(), planId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/plans/current")
    public ResponseEntity<Map<String, Object>> currentPlan() {
        PlanService.PlanRow plan = plans.activePlan(CurrentUser.id());
        if (plan == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(describe(plan));
    }

    @GetMapping("/plans/{planId}")
    public Map<String, Object> plan(@PathVariable UUID planId) {
        return describe(plans.requireOwned(CurrentUser.id(), planId));
    }

    @GetMapping("/plans/{planId}/capacity-check")
    public CapacityCalculator.Result capacityCheck(@PathVariable UUID planId) {
        return plans.capacityCheck(CurrentUser.id(), planId);
    }

    /** Analysis progress, polled by the onboarding screen. */
    @GetMapping("/plans/{planId}/analysis")
    public AnalysisProgressService.View analysis(@PathVariable UUID planId) {
        return analysisProgress.progress(CurrentUser.id(), planId);
    }

    /**
     * What this plan's AI generation actually used.
     *
     * <p>Owned by the learner's plan rather than by an admin screen, because the
     * question it answers - "was this lesson written by a real model, and which
     * one" - is the learner's. No credential and no prompt is exposed; the model
     * ids come from the ledger, so they say what served <em>this</em> plan rather
     * than what is configured today.
     */
    @GetMapping("/plans/{planId}/ai-usage")
    public Map<String, Object> aiUsage(@PathVariable UUID planId) {
        plans.requireOwned(CurrentUser.id(), planId);
        AiCallLedger.Usage usage = ledger.usageFor(planId);
        return Map.of(
                "provenance", provenance.forPlan(planId).name(),
                "models", usage.models(),
                "tokensIn", usage.tokensIn(),
                "tokensOut", usage.tokensOut(),
                "providerCalls", usage.providerCalls(),
                "cacheHits", usage.cacheHits(),
                "rejectedAttempts", usage.rejectedAttempts(),
                "costCents", usage.costCents(),
                // An estimate presented as a measurement is worse than no number.
                "pricingConfigured", usage.models().stream().anyMatch(aiProps::hasPublishedPrice));
    }

    @GetMapping("/plans/{planId}/coverage")
    public MappingService.CoverageReport coverage(@PathVariable UUID planId) {
        PlanService.PlanRow plan = plans.requireOwned(CurrentUser.id(), planId);
        if (plan.materialRevisionId() == null) {
            throw new PlanService.PlanException("NO_MATERIAL", "plan has no analysed material yet");
        }
        return mappings.coverageReport(plan.materialRevisionId(), plan.certificationVersionId(),
                provenance.forPlan(planId).name());
    }

    @GetMapping("/plans/{planId}/units")
    public List<Map<String, Object>> units(@PathVariable UUID planId) {
        plans.requireOwned(CurrentUser.id(), planId);
        return jdbc.queryForList(
                "SELECT pu.learning_unit_id AS id, u.title, u.page_start, u.page_end, "
                        + "       pu.effective_effort_minutes, pu.resolved_relevance, pu.marked_known, "
                        + "       pu.status "
                        + "  FROM plan_unit pu JOIN learning_unit u ON u.id = pu.learning_unit_id "
                        + " WHERE pu.plan_id = ? ORDER BY pu.order_index", planId);
    }

    @PatchMapping("/plans/{planId}/known-units")
    public ResponseEntity<Void> markKnown(@PathVariable UUID planId,
                                          @RequestBody @Valid KnownUnitsRequest request) {
        plans.markUnitsKnown(CurrentUser.id(), planId, request.unitIds(), request.known());
        return ResponseEntity.noContent().build();
    }

    /**
     * Runs the scheduler for the first time.
     *
     * <p>An infeasible plan answers 409 with concrete options and writes nothing:
     * a schedule that cannot be finished is worse than an honest refusal.
     */
    @PostMapping("/plans/{planId}/activate")
    public ResponseEntity<?> activate(@PathVariable UUID planId) {
        PlanService.PlanRow plan = plans.requireOwned(CurrentUser.id(), planId);
        if (!List.of("READY_FOR_REVIEW", "ACTIVE").contains(plan.status())) {
            throw new PlanService.PlanException("PLAN_NOT_READY",
                    "plan is " + plan.status() + "; material analysis must finish first");
        }

        SchedulingResult result = scheduling.reschedule(planId, "INITIAL", List.of());
        if (result instanceof SchedulingResult.Infeasible infeasible) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "SCHEDULE_INFEASIBLE",
                    "deficitMinutes", infeasible.deficitMinutes(),
                    "availableMinutes", infeasible.availableMinutes(),
                    "requiredMinutes", infeasible.requiredMinutes(),
                    "suggestions", infeasible.suggestions()));
        }

        jdbc.update("UPDATE study_plan SET status = 'ACTIVE', activated_at = now() "
                + " WHERE id = ? AND status <> 'ACTIVE'", planId);
        dayCompletion.enqueueUpcomingContent(planId);

        SchedulingResult.Scheduled scheduled = (SchedulingResult.Scheduled) result;
        return ResponseEntity.ok(Map.of(
                "planId", planId,
                "status", "ACTIVE",
                "dayCount", scheduled.days().size(),
                "droppedUnits", scheduled.droppedUnits(),
                "compressionMode", scheduled.compressionMode(),
                "adjustments", scheduled.adjustments()));
    }

    /** Changing exam date or availability replans the remaining days. */
    @PatchMapping("/plans/{planId}/constraints")
    public ResponseEntity<?> updateConstraints(@PathVariable UUID planId,
                                               @RequestBody ConstraintsRequest request) {
        plans.updateConstraints(CurrentUser.id(), planId, request.examDate(),
                request.weeklyCapacity(), request.blockedDates());

        SchedulingResult result = scheduling.reschedule(planId, "CONSTRAINTS_CHANGED",
                weakTopics.detect(planId).stream()
                        .map(w -> new SchedulingService.WeakTopic(w.courseTopicId(), w.weaknessScore()))
                        .toList());

        if (result instanceof SchedulingResult.Infeasible infeasible) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "SCHEDULE_INFEASIBLE",
                    "deficitMinutes", infeasible.deficitMinutes(),
                    "suggestions", infeasible.suggestions()));
        }
        SchedulingResult.Scheduled scheduled = (SchedulingResult.Scheduled) result;
        return ResponseEntity.ok(Map.of(
                "dayCount", scheduled.days().size(),
                "droppedUnits", scheduled.droppedUnits(),
                "adjustments", scheduled.adjustments()));
    }

    // ------------------------------------------------------------- daily loop

    @GetMapping("/today")
    public TodayService.TodayView today() {
        return today.today(CurrentUser.id());
    }

    @GetMapping("/progress")
    public TodayService.ProgressView progress() {
        return today.progress(CurrentUser.id());
    }

    @GetMapping("/plans/{planId}/days")
    public List<TodayService.PlanDay> days(@PathVariable UUID planId) {
        return today.planDays(CurrentUser.id(), planId);
    }

    @PostMapping("/plans/{planId}/items/{itemId}/complete")
    public ResponseEntity<Void> completeItem(@PathVariable UUID planId, @PathVariable UUID itemId) {
        dayCompletion.completeItem(CurrentUser.id(), planId, itemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/plans/{planId}/days/{dayId}/complete")
    public DayCompletionService.CompletionResult completeDay(@PathVariable UUID planId,
                                                             @PathVariable UUID dayId) {
        return dayCompletion.completeDay(CurrentUser.id(), planId, dayId);
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> describe(PlanService.PlanRow plan) {
        Map<String, Object> version = jdbc.queryForMap(
                "SELECT cv.exam_code, cv.version_label, c.name, c.provider "
                        + "  FROM certification_version cv JOIN certification c ON c.id = cv.certification_id "
                        + " WHERE cv.id = ?", plan.certificationVersionId());

        return Map.of(
                "id", plan.id(),
                "status", plan.status(),
                "examDate", plan.examDate().toString(),
                "startDate", plan.startDate().toString(),
                "certificationVersionId", plan.certificationVersionId(),
                "certification", version,
                "weeklyCapacity", plan.weeklyCapacity().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                e -> e.getKey().name(), Map.Entry::getValue)),
                "blockedDates", plan.blockedDates().stream().map(LocalDate::toString).sorted().toList(),
                "compressionMode", plan.compressionMode(),
                "hasMaterial", plan.materialRevisionId() != null);
    }

    // --------------------------------------------------------------- requests

    public record CapacityPreviewRequest(
            @NotNull UUID certificationVersionId,
            @NotNull LocalDate examDate,
            @NotNull Map<String, Integer> weeklyCapacity,
            List<LocalDate> blockedDates) {
    }

    public record CreatePlanRequest(
            @NotNull UUID certificationVersionId,
            @NotNull LocalDate examDate,
            @NotNull Map<String, Integer> weeklyCapacity,
            List<LocalDate> blockedDates,
            LocalDate startDate) {
    }

    public record KnownUnitsRequest(@NotNull List<UUID> unitIds, boolean known) {
    }

    public record ConstraintsRequest(LocalDate examDate, Map<String, Integer> weeklyCapacity,
                                     List<LocalDate> blockedDates) {
    }
}
