package com.certcopilot.domain.planning;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.domain.assessment.FinalReviewExamService;
import com.certcopilot.domain.assessment.WeakTopicDetector;
import com.certcopilot.domain.mapping.MappingService;
import com.certcopilot.platform.ai.AiProvenance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composed read models for the two screens that matter.
 *
 * <p>Reads are purpose-built rather than resource-shaped: the Today screen needs
 * data from six tables across four modules, and if the client assembled it the
 * client would contain business logic. Composing server-side is what keeps the
 * contract reusable by a future mobile app instead of being re-implemented there.
 */
@Service
public class TodayService {

    private final JdbcTemplate jdbc;
    private final PlanService plans;
    private final DayCompletionService dayCompletion;
    private final WeakTopicDetector weakTopics;
    private final MappingService mappings;
    private final AiProvenance provenance;
    private final FinalReviewExamService finalExam;

    public TodayService(JdbcTemplate jdbc,
                        PlanService plans,
                        DayCompletionService dayCompletion,
                        WeakTopicDetector weakTopics,
                        MappingService mappings,
                        FinalReviewExamService finalExam,
                        AiProvenance provenance) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.dayCompletion = dayCompletion;
        this.weakTopics = weakTopics;
        this.mappings = mappings;
        this.finalExam = finalExam;
        this.provenance = provenance;
    }

    /**
     * Everything the home screen needs in one call.
     *
     * <p>Also catches up any missed days first, which is why the learner never
     * sees a stale plan after being away - no cron, just lazy evaluation on the
     * next visit.
     */
    @Transactional
    public TodayView today(UUID userId) {
        PlanService.PlanRow plan = plans.activePlan(userId);
        if (plan == null) {
            return TodayView.noPlan();
        }
        if ("COMPLETED".equals(plan.status())) {
            return allDoneView(plan);
        }
        if (!"ACTIVE".equals(plan.status())) {
            return TodayView.planNotReady(plan.id(), plan.status());
        }

        dayCompletion.carryForwardMissedDays(plan.id());

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> dayRows = jdbc.queryForList(
                "SELECT id, day_date, day_index, capacity_minutes, kind, status "
                        + "  FROM study_day WHERE plan_id = ? AND status IN ('PLANNED','IN_PROGRESS') "
                        + "   AND day_date >= ? ORDER BY day_date LIMIT 1", plan.id(), today);

        if (dayRows.isEmpty()) {
            return allDoneView(plan);
        }

        Map<String, Object> day = dayRows.get(0);
        UUID dayId = (UUID) day.get("id");

        List<TodayItem> items = jdbc.query(
                "SELECT i.id, i.item_type, i.learning_unit_id, i.course_topic_id, i.order_index, "
                        + "       i.allotted_minutes, i.status, "
                        + "       COALESCE(u.title, ct.title, 'Ôn tập') AS title, "
                        + "       u.page_start, u.page_end, r.material_id, "
                        + "       COALESCE(pu.resolved_relevance, 'MEDIUM') AS relevance, "
                        + "       EXISTS(SELECT 1 FROM learning_pack p "
                        + "               WHERE p.learning_unit_id = i.learning_unit_id "
                        + "                 AND p.cache_status = 'VALID') AS pack_ready, "
                        + "       EXISTS(SELECT 1 FROM material_page mp "
                        + "               WHERE mp.material_revision_id = u.material_revision_id "
                        + "                 AND mp.page_no BETWEEN u.page_start AND u.page_end "
                        + "                 AND mp.has_significant_visual) AS has_visual "
                        + "  FROM study_day_item i "
                        + "  LEFT JOIN learning_unit u ON u.id = i.learning_unit_id "
                        + "  LEFT JOIN material_revision r ON r.id = u.material_revision_id "
                        + "  LEFT JOIN course_topic ct ON ct.id = i.course_topic_id "
                        + "  LEFT JOIN plan_unit pu ON pu.plan_id = i.plan_id "
                        + "                        AND pu.learning_unit_id = i.learning_unit_id "
                        + " WHERE i.study_day_id = ? ORDER BY i.order_index",
                (rs, n) -> new TodayItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("item_type"),
                        rs.getObject("learning_unit_id", UUID.class),
                        rs.getObject("course_topic_id", UUID.class),
                        rs.getString("title"),
                        (Integer) rs.getObject("page_start"),
                        (Integer) rs.getObject("page_end"),
                        rs.getObject("material_id", UUID.class),
                        rs.getInt("allotted_minutes"),
                        rs.getString("status"),
                        rs.getBoolean("pack_ready"),
                        rs.getBoolean("has_visual"),
                        rs.getString("relevance")),
                dayId);

        Integer warmupCount = jdbc.queryForObject(
                "SELECT count(*) FROM warmup_queue WHERE plan_id = ? AND consumed_at IS NULL",
                Integer.class, plan.id());

        Integer totalDays = jdbc.queryForObject(
                "SELECT count(*) FROM study_day WHERE plan_id = ?", Integer.class, plan.id());
        Integer completedDays = jdbc.queryForObject(
                "SELECT count(*) FROM study_day WHERE plan_id = ? AND status = 'COMPLETED'",
                Integer.class, plan.id());

        boolean quizAvailable = items.stream()
                .anyMatch(i -> i.learningUnitId() != null)
                && hasQuestionsFor(plan.id(), dayId);

        return new TodayView(
                true, plan.id(), "ACTIVE", null,
                dayId,
                String.valueOf(day.get("day_date")),
                ((Number) day.get("day_index")).intValue(),
                totalDays == null ? 0 : totalDays,
                completedDays == null ? 0 : completedDays,
                ((Number) day.get("capacity_minutes")).intValue(),
                String.valueOf(day.get("kind")),
                items,
                warmupCount == null ? 0 : warmupCount,
                quizAvailable,
                examCountdown(plan),
                finalExam.readiness(plan.id()));
    }

    /**
     * The finished-schedule view.
     *
     * <p>Carries the day totals rather than zeroes: a learner who has finished the
     * schedule is exactly the one who wants to see how much they got through, and
     * a screen that answers "0 of 0" reads as though the plan never existed.
     */
    private TodayView allDoneView(PlanService.PlanRow plan) {
        Integer totalDays = jdbc.queryForObject(
                "SELECT count(*) FROM study_day WHERE plan_id = ?", Integer.class, plan.id());
        Integer completedDays = jdbc.queryForObject(
                "SELECT count(*) FROM study_day WHERE plan_id = ? AND status = 'COMPLETED'",
                Integer.class, plan.id());
        return TodayView.allDone(plan.id(), plan.status(), examCountdown(plan),
                finalExam.readiness(plan.id()),
                totalDays == null ? 0 : totalDays,
                completedDays == null ? 0 : completedDays);
    }

    private boolean hasQuestionsFor(UUID planId, UUID dayId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM question q WHERE q.plan_id = ? AND q.validation_status = 'VALID' "
                        + "   AND q.learning_unit_id IN (SELECT learning_unit_id FROM study_day_item "
                        + "        WHERE study_day_id = ? AND learning_unit_id IS NOT NULL)",
                Integer.class, planId, dayId);
        return n != null && n > 0;
    }

    /** Progress by exam domain, plus the adjustment log so schedule changes are visible. */
    @Transactional(readOnly = true)
    public ProgressView progress(UUID userId) {
        PlanService.PlanRow plan = plans.activePlan(userId);
        if (plan == null) {
            throw new PlanService.PlanException("NO_ACTIVE_PLAN", "no active plan");
        }

        List<DomainProgress> domains = jdbc.query(
                "SELECT d.code, d.title, d.weight_percent, "
                        + "       COALESCE(SUM(tm.correct_count), 0) AS correct, "
                        + "       COALESCE(SUM(tm.total_count), 0) AS total, "
                        + "       count(DISTINCT tm.course_topic_id) FILTER "
                        + "             (WHERE tm.status = 'MASTERED') AS mastered_topics, "
                        + "       count(DISTINCT tm.course_topic_id) AS assessed_topics "
                        + "  FROM exam_domain d "
                        + "  LEFT JOIN task_statement t ON t.exam_domain_id = d.id "
                        + "  LEFT JOIN unit_exam_mapping m ON m.task_statement_id = t.id "
                        + "  LEFT JOIN learning_unit u ON u.id = m.learning_unit_id "
                        + "  LEFT JOIN topic_mastery tm ON tm.plan_id = ? "
                        + "        AND jsonb_exists(u.course_topic_ids, tm.course_topic_id::text) "
                        + " WHERE d.certification_version_id = ? "
                        + " GROUP BY d.code, d.title, d.weight_percent, d.order_index "
                        + " ORDER BY d.order_index",
                (rs, n) -> new DomainProgress(
                        rs.getString("code"), rs.getString("title"), rs.getInt("weight_percent"),
                        rs.getInt("correct"), rs.getInt("total"),
                        rs.getInt("mastered_topics"), rs.getInt("assessed_topics")),
                plan.id(), plan.certificationVersionId());

        List<Map<String, Object>> adjustments = jdbc.queryForList(
                "SELECT trigger, reason_code, params, created_at FROM plan_adjustment "
                        + " WHERE plan_id = ? ORDER BY created_at DESC LIMIT 20", plan.id());

        Integer totalDays = jdbc.queryForObject(
                "SELECT count(*) FROM study_day WHERE plan_id = ?", Integer.class, plan.id());
        Integer completedDays = jdbc.queryForObject(
                "SELECT count(*) FROM study_day WHERE plan_id = ? AND status = 'COMPLETED'",
                Integer.class, plan.id());
        Integer skippedDays = jdbc.queryForObject(
                "SELECT count(*) FROM study_day WHERE plan_id = ? AND status = 'SKIPPED'",
                Integer.class, plan.id());

        MappingService.CoverageReport coverage = plan.materialRevisionId() == null ? null
                : mappings.coverageReport(plan.materialRevisionId(), plan.certificationVersionId(),
                        provenance.forPlan(plan.id()).name());

        return new ProgressView(
                plan.id(),
                totalDays == null ? 0 : totalDays,
                completedDays == null ? 0 : completedDays,
                skippedDays == null ? 0 : skippedDays,
                examCountdown(plan),
                domains,
                weakTopics.detect(plan.id()),
                adjustments,
                coverage,
                finalExam.readiness(plan.id()));
    }

    /** The whole schedule, for the plan screen. */
    @Transactional(readOnly = true)
    public List<PlanDay> planDays(UUID userId, UUID planId) {
        plans.requireOwned(userId, planId);
        List<PlanDay> days = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT d.id, d.day_date, d.day_index, d.capacity_minutes, d.kind, d.status, "
                        + "       COALESCE(SUM(i.allotted_minutes), 0) AS allocated, "
                        + "       count(i.id) AS item_count "
                        + "  FROM study_day d LEFT JOIN study_day_item i ON i.study_day_id = d.id "
                        + " WHERE d.plan_id = ? "
                        + " GROUP BY d.id, d.day_date, d.day_index, d.capacity_minutes, d.kind, d.status "
                        + " ORDER BY d.day_date", planId)) {
            UUID dayId = (UUID) row.get("id");
            List<String> titles = jdbc.queryForList(
                    "SELECT COALESCE(u.title, ct.title, 'Ôn tập') FROM study_day_item i "
                            + "  LEFT JOIN learning_unit u ON u.id = i.learning_unit_id "
                            + "  LEFT JOIN course_topic ct ON ct.id = i.course_topic_id "
                            + " WHERE i.study_day_id = ? ORDER BY i.order_index",
                    String.class, dayId);
            days.add(new PlanDay(
                    dayId, String.valueOf(row.get("day_date")),
                    ((Number) row.get("day_index")).intValue(),
                    ((Number) row.get("capacity_minutes")).intValue(),
                    ((Number) row.get("allocated")).intValue(),
                    String.valueOf(row.get("kind")), String.valueOf(row.get("status")),
                    titles));
        }
        return days;
    }

    private int examCountdown(PlanService.PlanRow plan) {
        return (int) LocalDate.now().datesUntil(plan.examDate()).count();
    }

    // --------------------------------------------------------------- records

    /**
     * @param relevance how much this unit matters to the exam, resolved for this
     *                  plan. On screen it is the difference between "read this
     *                  carefully" and "skim it", which is most of what a learner
     *                  short on time needs from the list.
     */
    public record TodayItem(UUID itemId, String type, UUID learningUnitId, UUID courseTopicId,
                            String title, Integer pageStart, Integer pageEnd, UUID materialId,
                            int allottedMinutes, String status, boolean packReady,
                            boolean hasSignificantVisual, String relevance) {
    }

    public record TodayView(boolean hasPlan, UUID planId, String planStatus, String message,
                            UUID studyDayId, String date, int dayIndex, int totalDays,
                            int completedDays, int capacityMinutes, String kind,
                            List<TodayItem> items, int warmupAvailable, boolean quizAvailable,
                            int daysUntilExam, FinalReviewExamService.Readiness finalExam) {

        static TodayView noPlan() {
            return new TodayView(false, null, null,
                    "Bạn chưa có kế hoạch học nào. Hãy tạo một kế hoạch để bắt đầu.",
                    null, null, 0, 0, 0, 0, null, List.of(), 0, false, 0, null);
        }

        static TodayView planNotReady(UUID planId, String status) {
            return new TodayView(true, planId, status,
                    "Kế hoạch đang được chuẩn bị.",
                    null, null, 0, 0, 0, 0, null, List.of(), 0, false, 0, null);
        }

        /**
         * Every day is finished. The readiness is passed on purpose: without it
         * the client cannot offer the Final Review Exam, and this is the only
         * screen that offers it.
         */
        static TodayView allDone(UUID planId, String status, int daysUntilExam,
                                 FinalReviewExamService.Readiness finalExam,
                                 int totalDays, int completedDays) {
            return new TodayView(true, planId, status,
                    "Bạn đã hoàn thành toàn bộ lịch học. Hãy làm bài ôn tổng hợp.",
                    null, null, 0, totalDays, completedDays, 0, null, List.of(), 0, false,
                    daysUntilExam, finalExam);
        }
    }

    public record DomainProgress(String code, String title, int weightPercent,
                                 int correct, int total, int masteredTopics, int assessedTopics) {
        /** Derived, so it must be declared or Jackson would omit it from the response. */
        @com.fasterxml.jackson.annotation.JsonProperty("accuracyPercent")
        public int accuracyPercent() {
            return total == 0 ? 0 : (int) Math.round(100.0 * correct / total);
        }
    }

    public record ProgressView(UUID planId, int totalDays, int completedDays, int skippedDays,
                               int daysUntilExam, List<DomainProgress> domains,
                               List<WeakTopicDetector.WeakTopic> weakTopics,
                               List<Map<String, Object>> adjustments,
                               MappingService.CoverageReport coverage,
                               FinalReviewExamService.Readiness finalExam) {
    }

    public record PlanDay(UUID id, String date, int dayIndex, int capacityMinutes,
                          int allocatedMinutes, String kind, String status, List<String> itemTitles) {
    }
}
