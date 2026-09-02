package com.certcopilot.domain.planning;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.domain.planning.internal.scheduler.PlanningContext;
import com.certcopilot.domain.planning.internal.scheduler.SchedulerEngine;
import com.certcopilot.domain.planning.internal.scheduler.SchedulingResult;
import com.certcopilot.shared.DepthFlag;
import com.certcopilot.shared.ExamRelevance;
import com.certcopilot.shared.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads plan state, calls the pure scheduler, writes the result.
 *
 * <p>All four replan triggers - initial planning, a missed day, a weak topic, a
 * constraints change - go through {@link #reschedule}. One algorithm, one set of
 * invariants, one place to fix a bug.
 *
 * <p>Only {@code study_day_item} rows are rewritten. Learning units and generated
 * packs are untouched, which is why a replan costs nothing in model calls.
 */
@Service
public class SchedulingService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);

    /** Minutes given to a weak-topic review block. Short by design: it is a refresher. */
    private static final int REVIEW_BLOCK_MINUTES = 15;

    private final JdbcTemplate jdbc;
    private final PlanService plans;

    public SchedulingService(JdbcTemplate jdbc, PlanService plans) {
        this.jdbc = jdbc;
        this.plans = plans;
    }

    /**
     * Rebuilds the remaining schedule.
     *
     * @param trigger why this ran; recorded on the adjustment for the learner
     * @return the engine's result; {@code Infeasible} means nothing was written
     */
    @Transactional
    public SchedulingResult reschedule(UUID planId, String trigger, List<WeakTopic> weakTopics) {
        // Serialises concurrent replans for the same plan, so a double-tap on
        // "complete day" cannot interleave two rewrites.
        // pg_advisory_xact_lock returns void, so the result is discarded; the lock
        // is held until this transaction ends.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", planId.toString());

        PlanService.PlanRow plan = plans.load(planId);
        LocalDate today = LocalDate.now();

        List<PlanningContext.FrozenDay> frozen = jdbc.query(
                "SELECT day_date, COALESCE(SUM(i.allotted_minutes), 0) AS used "
                        + "  FROM study_day d LEFT JOIN study_day_item i ON i.study_day_id = d.id "
                        + " WHERE d.plan_id = ? AND d.status = 'COMPLETED' "
                        + " GROUP BY d.day_date ORDER BY d.day_date",
                (rs, n) -> new PlanningContext.FrozenDay(
                        rs.getDate("day_date").toLocalDate(), rs.getInt("used")),
                planId);

        List<PlanningContext.SchedulableUnit> pending = loadPendingUnits(planId);

        List<PlanningContext.ReviewBlock> reviews = weakTopics.stream()
                .map(w -> new PlanningContext.ReviewBlock(
                        w.courseTopicId(), REVIEW_BLOCK_MINUTES, w.weaknessScore()))
                .toList();

        PlanningContext context = new PlanningContext(
                today, plan.examDate(), plan.weeklyCapacity(), plan.blockedDates(),
                frozen, pending, reviews, PlanningContext.SchedulerConfig.defaults());

        SchedulingResult result = SchedulerEngine.plan(context);

        if (result instanceof SchedulingResult.Infeasible infeasible) {
            // Nothing is persisted. An unachievable schedule that looks fine is
            // worse than an honest refusal with options.
            log.info("plan {} is infeasible: deficit {} minutes", planId, infeasible.deficitMinutes());
            return result;
        }

        SchedulingResult.Scheduled scheduled = (SchedulingResult.Scheduled) result;
        persist(planId, scheduled, trigger);
        return scheduled;
    }

    @Transactional
    public void persist(UUID planId, SchedulingResult.Scheduled scheduled, String trigger) {
        String snapshotBefore = snapshot(planId);

        // Rewrite only what is not frozen.
        jdbc.update(
                "DELETE FROM study_day_item WHERE plan_id = ? AND study_day_id IN "
                        + " (SELECT id FROM study_day WHERE plan_id = ? AND status <> 'COMPLETED')",
                planId, planId);
        jdbc.update("DELETE FROM study_day WHERE plan_id = ? AND status <> 'COMPLETED'", planId);

        for (SchedulingResult.ScheduledDay day : scheduled.days()) {
            UUID dayId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO study_day (id, plan_id, day_date, day_index, capacity_minutes, kind, status) "
                            + "VALUES (?,?,?,?,?,?, 'PLANNED') "
                            + "ON CONFLICT (plan_id, day_date) DO NOTHING",
                    dayId, planId, day.date(), day.dayIndex(), day.capacityMinutes(), day.kind().name());

            // The conflict clause means an existing frozen day keeps its id.
            UUID actualDayId = jdbc.queryForObject(
                    "SELECT id FROM study_day WHERE plan_id = ? AND day_date = ?",
                    UUID.class, planId, day.date());

            for (SchedulingResult.ScheduledItem item : day.items()) {
                jdbc.update(
                        "INSERT INTO study_day_item (id, study_day_id, plan_id, item_type, "
                                + " learning_unit_id, course_topic_id, order_index, allotted_minutes) "
                                + "VALUES (?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), actualDayId, planId, item.type().name(),
                        item.learningUnitId(), item.courseTopicId(),
                        item.orderIndex(), item.allottedMinutes());
            }
        }

        for (SchedulingResult.DroppedUnit dropped : scheduled.droppedUnits()) {
            jdbc.update("UPDATE plan_unit SET status = 'DROPPED', dropped_reason = ? "
                            + " WHERE plan_id = ? AND learning_unit_id = ?",
                    dropped.reasonCode(), planId, dropped.learningUnitId());
        }

        jdbc.update("UPDATE study_plan SET compression_mode = ?, effort_ratio = ? WHERE id = ?",
                scheduled.compressionMode(),
                Double.isFinite(scheduled.effortRatio()) ? scheduled.effortRatio() : null,
                planId);

        // Reason codes are template keys, not model output: cheaper, consistent,
        // and translatable.
        for (SchedulingResult.Adjustment adjustment : scheduled.adjustments()) {
            jdbc.update(
                    "INSERT INTO plan_adjustment (id, plan_id, trigger, reason_code, params, schedule_snapshot) "
                            + "VALUES (?,?,?,?,?::jsonb,?::jsonb)",
                    UUID.randomUUID(), planId, trigger, adjustment.reasonCode(),
                    Json.write(Map.of("detail", adjustment.detail())), snapshotBefore);
        }
        if (scheduled.adjustments().isEmpty()) {
            jdbc.update(
                    "INSERT INTO plan_adjustment (id, plan_id, trigger, reason_code, params, schedule_snapshot) "
                            + "VALUES (?,?,?, 'SCHEDULE_REBUILT', '{}'::jsonb, ?::jsonb)",
                    UUID.randomUUID(), planId, trigger, snapshotBefore);
        }
    }

    private List<PlanningContext.SchedulableUnit> loadPendingUnits(UUID planId) {
        return jdbc.query(
                "SELECT pu.learning_unit_id, pu.order_index, pu.effective_effort_minutes, "
                        + "       pu.resolved_relevance, pu.depth_flag, pu.marked_known "
                        + "  FROM plan_unit pu "
                        + " WHERE pu.plan_id = ? AND pu.status = 'PENDING' "
                        + "   AND pu.learning_unit_id NOT IN ( "
                        + "        SELECT i.learning_unit_id FROM study_day_item i "
                        + "          JOIN study_day d ON d.id = i.study_day_id "
                        + "         WHERE i.plan_id = ? AND i.learning_unit_id IS NOT NULL "
                        + "           AND (i.status = 'DONE' OR d.status = 'COMPLETED')) "
                        + " ORDER BY pu.order_index",
                (rs, n) -> new PlanningContext.SchedulableUnit(
                        rs.getObject("learning_unit_id", UUID.class),
                        rs.getInt("order_index"),
                        rs.getInt("effective_effort_minutes"),
                        ExamRelevance.valueOf(rs.getString("resolved_relevance")),
                        DepthFlag.valueOf(rs.getString("depth_flag")),
                        rs.getBoolean("marked_known")),
                planId, planId);
    }

    /** Compact before-state used to show the learner what actually changed. */
    private String snapshot(UUID planId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.day_date, i.item_type, i.learning_unit_id, i.allotted_minutes "
                        + "  FROM study_day d LEFT JOIN study_day_item i ON i.study_day_id = d.id "
                        + " WHERE d.plan_id = ? ORDER BY d.day_date, i.order_index", planId);
        Map<String, List<String>> byDate = new HashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.get("item_type") == null) {
                continue;
            }
            byDate.computeIfAbsent(String.valueOf(row.get("day_date")), k -> new ArrayList<>())
                    .add(row.get("item_type") + ":" + row.get("learning_unit_id")
                            + ":" + row.get("allotted_minutes"));
        }
        return Json.write(byDate);
    }

    /** A topic the deterministic detector flagged, with its ranking score. */
    public record WeakTopic(UUID courseTopicId, double weaknessScore) {
    }
}
