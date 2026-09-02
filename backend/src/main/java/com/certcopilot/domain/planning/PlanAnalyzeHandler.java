package com.certcopilot.domain.planning;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.domain.mapping.MappingService;
import com.certcopilot.domain.material.EffortEstimator;
import com.certcopilot.platform.jobs.JobHandler;
import com.certcopilot.platform.jobs.JobRecord;
import com.certcopilot.shared.ExamRelevance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a processed material revision into a plannable set of units.
 *
 * <p>Three deterministic steps after the mapping call:
 *
 * <ol>
 *   <li>resolve each unit's relevance from its strongest mapping;
 *   <li>apply the relevance weight to base effort, producing plan-level effort;
 *   <li>normalise the total against the learner's real capacity.
 * </ol>
 *
 * <p>Step three is the one that matters. Absolute per-page timings vary hugely
 * between learners and decks; the ratio between units is the reliable part. Skip
 * normalisation and the schedule is either laughably light or impossible.
 */
@Component
public class PlanAnalyzeHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PlanAnalyzeHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String JOB_TYPE = "PLAN_ANALYZE";

    private final JdbcTemplate jdbc;
    private final PlanService plans;
    private final MappingService mappings;

    public PlanAnalyzeHandler(JdbcTemplate jdbc, PlanService plans, MappingService mappings) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.mappings = mappings;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(JobRecord job) throws Exception {
        UUID planId = UUID.fromString(MAPPER.readTree(job.payload()).path("planId").asText());
        analyze(planId);
    }

    @Transactional
    public void analyze(UUID planId) {
        PlanService.PlanRow plan = plans.load(planId);
        if (plan.materialRevisionId() == null) {
            log.warn("plan {} has no material revision to analyse", planId);
            return;
        }

        MappingService.MappingOutcome outcome =
                mappings.mapRevision(planId, plan.materialRevisionId(), plan.certificationVersionId());

        Map<UUID, ExamRelevance> relevanceByUnit =
                mappings.resolvedRelevance(plan.materialRevisionId(), plan.certificationVersionId());

        List<Map<String, Object>> units = jdbc.queryForList(
                "SELECT id, sequence, base_effort_minutes FROM learning_unit "
                        + " WHERE material_revision_id = ? ORDER BY sequence",
                plan.materialRevisionId());

        // Weighted effort before normalisation.
        int weightedTotal = 0;
        for (Map<String, Object> unit : units) {
            UUID unitId = (UUID) unit.get("id");
            ExamRelevance relevance = relevanceByUnit.getOrDefault(unitId, ExamRelevance.LOW);
            int base = ((Number) unit.get("base_effort_minutes")).intValue();
            weightedTotal += (int) Math.round(base * relevance.effortWeight());
        }

        int schedulable = schedulableMinutes(plan);
        double factor = EffortEstimator.normalisationFactor(weightedTotal, schedulable);

        jdbc.update("DELETE FROM plan_unit WHERE plan_id = ?", planId);
        for (Map<String, Object> unit : units) {
            UUID unitId = (UUID) unit.get("id");
            // A unit the model could not attach to any exam task is not deleted:
            // it is kept at LOW so the compression ladder can drop it first if
            // time runs short, and keep it if there is room.
            ExamRelevance relevance = relevanceByUnit.getOrDefault(unitId, ExamRelevance.LOW);
            int base = ((Number) unit.get("base_effort_minutes")).intValue();
            int weighted = (int) Math.round(base * relevance.effortWeight());
            int effective = EffortEstimator.applyNormalisation(weighted, factor);

            jdbc.update(
                    "INSERT INTO plan_unit (plan_id, learning_unit_id, order_index, "
                            + " effective_effort_minutes, resolved_relevance) VALUES (?,?,?,?,?)",
                    planId, unitId, ((Number) unit.get("sequence")).intValue(), effective,
                    relevance.name());
        }

        jdbc.update("UPDATE study_plan SET status = 'READY_FOR_REVIEW', mapping_version = ? WHERE id = ?",
                MappingService.MAPPING_VERSION, planId);

        log.info("plan {} analysed: {} units, {} mapped, normalisation factor {}",
                planId, units.size(), outcome.mappedUnitCount(), String.format("%.2f", factor));

        if (outcome.suspiciouslyLowMappingRate()) {
            // Almost always means the learner uploaded material for a different
            // certification. Say so rather than quietly building a bad plan.
            jdbc.update(
                    "INSERT INTO plan_adjustment (id, plan_id, trigger, reason_code, params) "
                            + "VALUES (?,?, 'INITIAL', 'LOW_MAPPING_RATE', ?::jsonb)",
                    UUID.randomUUID(), planId,
                    com.certcopilot.shared.Json.write(Map.of(
                            "mappedUnits", outcome.mappedUnitCount(),
                            "totalUnits", outcome.unitCount())));
        }
    }

    /** Capacity available for content, leaving the review window aside. */
    private int schedulableMinutes(PlanService.PlanRow plan) {
        LocalDate from = LocalDate.now().isAfter(plan.startDate()) ? LocalDate.now() : plan.startDate();
        int total = 0;
        for (LocalDate date = from; date.isBefore(plan.examDate()); date = date.plusDays(1)) {
            if (plan.blockedDates().contains(date)) {
                continue;
            }
            total += plan.weeklyCapacity().getOrDefault(date.getDayOfWeek(), 0);
        }
        // Reserve roughly the review window so content does not consume the days
        // set aside for revision and the final exam.
        return (int) Math.round(total * 0.85);
    }
}
