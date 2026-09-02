package com.certcopilot.domain.planning;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.domain.assessment.QuizService;
import com.certcopilot.domain.assessment.WeakTopicDetector;
import com.certcopilot.domain.planning.internal.scheduler.SchedulingResult;
import com.certcopilot.platform.jobs.JobQueue;
import com.certcopilot.shared.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The closed loop: completing a day updates mastery, finds weak topics, rebuilds
 * the remaining schedule and queues tomorrow's content.
 *
 * <p>This is the slice that makes the product something a chat assistant cannot
 * be. It runs as one transaction because every step is deterministic and local:
 * there is never a moment where the day is complete but the schedule has not
 * caught up. Anything slow or billable - generating tomorrow's lesson - is
 * queued as a job in the same transaction, which is the single boundary.
 */
@Service
public class DayCompletionService {

    private static final Logger log = LoggerFactory.getLogger(DayCompletionService.class);

    /** How far ahead content is generated. A learner who stops on day 3 has not paid for day 12. */
    private static final int PREGENERATE_DAYS_AHEAD = 2;

    private final JdbcTemplate jdbc;
    private final PlanService plans;
    private final SchedulingService scheduling;
    private final WeakTopicDetector weakTopics;
    private final QuizService quizService;
    private final JobQueue jobs;

    public DayCompletionService(JdbcTemplate jdbc,
                                PlanService plans,
                                SchedulingService scheduling,
                                WeakTopicDetector weakTopics,
                                QuizService quizService,
                                JobQueue jobs) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.scheduling = scheduling;
        this.weakTopics = weakTopics;
        this.quizService = quizService;
        this.jobs = jobs;
    }

    /**
     * Marks a unit finished. Kept separate from completing the day so a learner
     * can do one block on the commute and the rest later.
     */
    @Transactional
    public void completeItem(UUID userId, UUID planId, UUID itemId) {
        plans.requireOwned(userId, planId);
        jdbc.update("UPDATE study_day_item SET status = 'DONE', completed_at = now() "
                + " WHERE id = ? AND plan_id = ?", itemId, planId);
        jdbc.update(
                "UPDATE plan_unit SET status = 'DONE' WHERE plan_id = ? AND learning_unit_id = "
                        + " (SELECT learning_unit_id FROM study_day_item WHERE id = ?)",
                planId, itemId);
        jdbc.update("UPDATE study_day SET status = 'IN_PROGRESS', started_at = COALESCE(started_at, now()) "
                + " WHERE id = (SELECT study_day_id FROM study_day_item WHERE id = ?) "
                + "   AND status = 'PLANNED'", itemId);
    }

    /**
     * The full chain. Deterministic throughout; the only queued work is content
     * generation for the days ahead.
     */
    @Transactional
    public CompletionResult completeDay(UUID userId, UUID planId, UUID studyDayId) {
        plans.requireOwned(userId, planId);

        // Serialise against a concurrent replan or a double submit.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", planId.toString());

        String status = jdbc.queryForObject(
                "SELECT status FROM study_day WHERE id = ? AND plan_id = ?",
                String.class, studyDayId, planId);
        if ("COMPLETED".equals(status)) {
            // Idempotent: pressing the button twice must not run the chain twice.
            return currentState(planId, studyDayId, List.of(), null);
        }

        jdbc.update("UPDATE study_day SET status = 'COMPLETED', completed_at = now() WHERE id = ?",
                studyDayId);

        // Unfinished work is carried forward, never silently dropped.
        int carried = jdbc.update(
                "UPDATE study_day_item SET status = 'CARRIED_FORWARD' "
                        + " WHERE study_day_id = ? AND status IN ('PENDING','IN_PROGRESS')", studyDayId);

        jdbc.update("UPDATE plan_unit SET status = 'DONE' WHERE plan_id = ? AND learning_unit_id IN "
                        + " (SELECT learning_unit_id FROM study_day_item "
                        + "   WHERE study_day_id = ? AND status = 'DONE' AND learning_unit_id IS NOT NULL)",
                planId, studyDayId);

        quizService.recomputeMastery(planId);
        List<WeakTopicDetector.WeakTopic> weak = weakTopics.detect(planId);

        SchedulingResult result = scheduling.reschedule(planId, "DAY_COMPLETED",
                weak.stream()
                        .map(w -> new SchedulingService.WeakTopic(w.courseTopicId(), w.weaknessScore()))
                        .toList());

        markPlanCompleteIfFinished(planId);
        enqueueUpcomingContent(planId);

        log.info("plan {} day {} completed: {} items carried forward, {} weak topic(s)",
                planId, studyDayId, carried, weak.size());

        return currentState(planId, studyDayId, weak, result);
    }

    /**
     * Catches up a plan whose owner disappeared for a few days. Runs lazily on
     * the next visit, so no cron is needed.
     */
    @Transactional
    public boolean carryForwardMissedDays(UUID planId) {
        List<UUID> missed = jdbc.queryForList(
                "SELECT id FROM study_day WHERE plan_id = ? AND day_date < CURRENT_DATE "
                        + "   AND status IN ('PLANNED','IN_PROGRESS')",
                UUID.class, planId);
        if (missed.isEmpty()) {
            return false;
        }

        jdbc.update("UPDATE study_day_item SET status = 'CARRIED_FORWARD' "
                        + " WHERE study_day_id = ANY (?) AND status IN ('PENDING','IN_PROGRESS')",
                (Object) missed.toArray(new UUID[0]));
        jdbc.update("UPDATE study_day SET status = 'SKIPPED' WHERE id = ANY (?)",
                (Object) missed.toArray(new UUID[0]));

        List<WeakTopicDetector.WeakTopic> weak = weakTopics.detect(planId);
        scheduling.reschedule(planId, "DAY_MISSED",
                weak.stream()
                        .map(w -> new SchedulingService.WeakTopic(w.courseTopicId(), w.weaknessScore()))
                        .toList());

        log.info("plan {}: carried forward {} missed day(s)", planId, missed.size());
        return true;
    }

    /**
     * Queues lesson and quiz generation for the next couple of days.
     *
     * <p>Bounded on purpose: this is the main cost valve. Generating the whole
     * plan up front would bill for content most learners never reach.
     */
    @Transactional
    public void enqueueUpcomingContent(UUID planId) {
        List<Map<String, Object>> upcoming = jdbc.queryForList(
                "SELECT i.learning_unit_id FROM study_day_item i "
                        + "  JOIN study_day d ON d.id = i.study_day_id "
                        + " WHERE i.plan_id = ? AND i.item_type = 'LEARNING_UNIT' "
                        + "   AND i.learning_unit_id IS NOT NULL "
                        + "   AND d.status IN ('PLANNED','IN_PROGRESS') "
                        + "   AND d.day_date <= CURRENT_DATE + ? "
                        + " ORDER BY d.day_date, i.order_index",
                planId, PREGENERATE_DAYS_AHEAD);

        for (Map<String, Object> row : upcoming) {
            UUID unitId = (UUID) row.get("learning_unit_id");
            jobs.enqueue("GENERATE_PACK", "pack:" + planId + ":" + unitId,
                    Json.write(Map.of("planId", planId.toString(), "learningUnitId", unitId.toString())), 3);
        }
    }

    private void markPlanCompleteIfFinished(UUID planId) {
        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM study_day WHERE plan_id = ? AND status IN ('PLANNED','IN_PROGRESS')",
                Integer.class, planId);
        if (remaining != null && remaining == 0) {
            jdbc.update("UPDATE study_plan SET status = 'COMPLETED', completed_at = now() "
                    + " WHERE id = ? AND status = 'ACTIVE'", planId);
        }
    }

    private CompletionResult currentState(UUID planId, UUID studyDayId,
                                          List<WeakTopicDetector.WeakTopic> weak,
                                          SchedulingResult result) {
        List<Map<String, Object>> next = jdbc.queryForList(
                "SELECT d.id, d.day_date, d.day_index, "
                        + "       (SELECT count(*) FROM study_day_item i WHERE i.study_day_id = d.id) AS items "
                        + "  FROM study_day d WHERE d.plan_id = ? AND d.status = 'PLANNED' "
                        + "   AND d.day_date >= CURRENT_DATE ORDER BY d.day_date LIMIT 1", planId);

        List<Map<String, Object>> adjustments = jdbc.queryForList(
                "SELECT reason_code, params, created_at FROM plan_adjustment "
                        + " WHERE plan_id = ? ORDER BY created_at DESC LIMIT 5", planId);

        boolean infeasible = result instanceof SchedulingResult.Infeasible;
        return new CompletionResult(
                studyDayId,
                next.isEmpty() ? null : (UUID) next.get(0).get("id"),
                next.isEmpty() ? null : String.valueOf(next.get(0).get("day_date")),
                weak,
                adjustments,
                infeasible,
                infeasible ? (SchedulingResult.Infeasible) result : null);
    }

    public record CompletionResult(
            UUID completedDayId,
            UUID nextDayId,
            String nextDayDate,
            List<WeakTopicDetector.WeakTopic> weakTopics,
            List<Map<String, Object>> recentAdjustments,
            boolean scheduleInfeasible,
            SchedulingResult.Infeasible infeasible) {
    }
}
