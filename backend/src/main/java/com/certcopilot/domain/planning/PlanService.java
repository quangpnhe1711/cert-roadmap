package com.certcopilot.domain.planning;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.certcopilot.domain.catalog.CatalogService;
import com.certcopilot.platform.ai.AiProperties;
import com.certcopilot.platform.ai.BudgetService;
import com.certcopilot.shared.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan lifecycle: draft, capacity check, constraints, ownership.
 *
 * <p>Scheduling itself lives in the pure engine; this class is the boundary that
 * loads state, calls it, and writes the result. Keeping the two apart is what
 * makes the engine property-testable.
 */
@Service
public class PlanService {

    private final JdbcTemplate jdbc;
    private final CatalogService catalog;
    private final BudgetService budget;
    private final AiProperties aiProps;

    public PlanService(JdbcTemplate jdbc, CatalogService catalog,
                       BudgetService budget, AiProperties aiProps) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.budget = budget;
        this.aiProps = aiProps;
    }

    /**
     * Creates the draft plan.
     *
     * <p>One plan in flight per user, enforced by a partial unique index: two
     * concurrent plans would have to share the same study capacity, which
     * multiplies complexity for no MVP value. That is a limit on how many plans
     * are <em>running</em>, not on how many a learner may ever have - finished
     * and archived plans stay in their history and a new plan starts beside them.
     *
     * <p>An unfinished draft is discarded silently, because starting over is what
     * the learner just did. An {@code ACTIVE} plan is not: dropping a schedule
     * someone is part-way through is not a side effect anyone should get by
     * accident, so it is refused with a code the client turns into a choice.
     */
    @Transactional
    public UUID createDraft(UUID userId, UUID certificationVersionId, LocalDate examDate,
                            Map<String, Integer> weeklyCapacity, List<LocalDate> blockedDates,
                            LocalDate startDate) {
        if (!catalog.versionExists(certificationVersionId)) {
            throw new PlanException("UNKNOWN_CERTIFICATION", "certification version not found");
        }
        LocalDate start = startDate == null ? LocalDate.now() : startDate;
        if (!examDate.isAfter(start)) {
            throw new PlanException("EXAM_DATE_IN_PAST", "exam date must be after the start date");
        }

        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM study_plan WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, userId);
        if (active != null && active > 0) {
            throw new PlanException("ACTIVE_PLAN_EXISTS",
                    "you already have an active study plan; finish or archive it first");
        }

        discardExistingDraft(userId);

        UUID planId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO study_plan (id, user_id, certification_version_id, exam_date, start_date, "
                        + " status, daily_capacity, blocked_dates) "
                        + "VALUES (?,?,?,?,?, 'DRAFT', ?::jsonb, ?::jsonb)",
                planId, userId, certificationVersionId, examDate, start,
                Json.write(normaliseCapacity(weeklyCapacity)),
                Json.write(blockedDates == null ? List.of() : blockedDates.stream().map(LocalDate::toString).toList()));

        budget.ensureBudget(planId, aiProps.getDefaultHardCapCents(), aiProps.getDefaultSoftCapCents());
        return planId;
    }

    /** Abandons an unfinished plan so the learner can start over without a conflict. */
    @Transactional
    public void discardExistingDraft(UUID userId) {
        jdbc.update("UPDATE study_plan SET status = 'ABANDONED' "
                + " WHERE user_id = ? AND status IN ('DRAFT','ANALYZING','READY_FOR_REVIEW')", userId);
    }

    /**
     * The first honest answer the product gives, before any upload or model call.
     * Deterministic and fast.
     */
    @Transactional(readOnly = true)
    public CapacityCalculator.Result capacityCheck(UUID userId, UUID planId) {
        PlanRow plan = requireOwned(userId, planId);
        Map<String, Object> version = jdbc.queryForMap(
                "SELECT typical_effort_low_minutes, typical_effort_high_minutes "
                        + "  FROM certification_version WHERE id = ?", plan.certificationVersionId());

        return CapacityCalculator.calculate(
                maxOf(LocalDate.now(), plan.startDate()),
                plan.examDate(),
                plan.weeklyCapacity(),
                plan.blockedDates(),
                ((Number) version.get("typical_effort_low_minutes")).intValue(),
                ((Number) version.get("typical_effort_high_minutes")).intValue());
    }

    /** Preview for the onboarding screen, before a plan row exists. */
    public CapacityCalculator.Result previewCapacity(UUID certificationVersionId,
                                                     LocalDate examDate,
                                                     Map<String, Integer> weeklyCapacity,
                                                     List<LocalDate> blockedDates) {
        Map<String, Object> version = jdbc.queryForMap(
                "SELECT typical_effort_low_minutes, typical_effort_high_minutes "
                        + "  FROM certification_version WHERE id = ?", certificationVersionId);
        return CapacityCalculator.calculate(
                LocalDate.now(), examDate,
                toDayOfWeekMap(normaliseCapacity(weeklyCapacity)),
                blockedDates == null ? Set.of() : new HashSet<>(blockedDates),
                ((Number) version.get("typical_effort_low_minutes")).intValue(),
                ((Number) version.get("typical_effort_high_minutes")).intValue());
    }

    @Transactional
    public void updateConstraints(UUID userId, UUID planId, LocalDate examDate,
                                  Map<String, Integer> weeklyCapacity, List<LocalDate> blockedDates) {
        PlanRow plan = requireOwned(userId, planId);
        LocalDate newExamDate = examDate == null ? plan.examDate() : examDate;
        if (!newExamDate.isAfter(LocalDate.now())) {
            throw new PlanException("EXAM_DATE_IN_PAST", "exam date must be in the future");
        }
        jdbc.update(
                "UPDATE study_plan SET exam_date = ?, "
                        + "       daily_capacity = COALESCE(?::jsonb, daily_capacity), "
                        + "       blocked_dates = COALESCE(?::jsonb, blocked_dates) "
                        + " WHERE id = ?",
                newExamDate,
                weeklyCapacity == null ? null : Json.write(normaliseCapacity(weeklyCapacity)),
                blockedDates == null ? null
                        : Json.write(blockedDates.stream().map(LocalDate::toString).toList()),
                planId);
    }

    @Transactional
    public void markUnitsKnown(UUID userId, UUID planId, List<UUID> unitIds, boolean known) {
        requireOwned(userId, planId);
        for (UUID unitId : unitIds) {
            jdbc.update("UPDATE plan_unit SET marked_known = ? WHERE plan_id = ? AND learning_unit_id = ?",
                    known, planId, unitId);
        }
    }

    /**
     * Every plan the learner has, newest first, with the active one on top.
     *
     * <p>Discarded drafts are left out: they are an artefact of the learner
     * pressing "start over", never something they chose to keep, and listing them
     * would make the history unreadable within a week.
     */
    @Transactional(readOnly = true)
    public List<PlanSummary> listPlans(UUID userId) {
        return jdbc.query(
                "SELECT p.id, p.status, p.exam_date, p.start_date, p.material_revision_id, "
                        + "       cv.exam_code, cv.version_label, c.name AS cert_name, c.provider, "
                        + "       (SELECT count(*) FROM study_day d WHERE d.plan_id = p.id) AS total_days, "
                        + "       (SELECT count(*) FROM study_day d WHERE d.plan_id = p.id "
                        + "               AND d.status = 'COMPLETED') AS completed_days "
                        + "  FROM study_plan p "
                        + "  JOIN certification_version cv ON cv.id = p.certification_version_id "
                        + "  JOIN certification c ON c.id = cv.certification_id "
                        + " WHERE p.user_id = ? AND p.status <> 'ABANDONED' "
                        + " ORDER BY (p.status = 'ACTIVE') DESC, p.created_at DESC",
                (rs, n) -> {
                    int totalDays = rs.getInt("total_days");
                    int completedDays = rs.getInt("completed_days");
                    LocalDate examDate = rs.getDate("exam_date").toLocalDate();
                    return new PlanSummary(
                            rs.getObject("id", UUID.class),
                            rs.getString("status"),
                            examDate.toString(),
                            rs.getDate("start_date").toLocalDate().toString(),
                            rs.getString("exam_code"),
                            rs.getString("version_label"),
                            rs.getString("cert_name"),
                            rs.getString("provider"),
                            totalDays,
                            completedDays,
                            totalDays == 0 ? 0 : (int) Math.round(100.0 * completedDays / totalDays),
                            (int) LocalDate.now().datesUntil(examDate.isAfter(LocalDate.now())
                                    ? examDate : LocalDate.now()).count(),
                            rs.getObject("material_revision_id", UUID.class) != null);
                },
                userId);
    }

    /**
     * Puts a plan away without deleting it.
     *
     * <p>Deleting would take the learner's own quiz history and the material
     * revisions completed days point at with it. Archiving frees the one-active
     * slot, which is the only thing the learner actually wanted.
     */
    @Transactional
    public void archive(UUID userId, UUID planId) {
        PlanRow plan = requireOwned(userId, planId);
        if ("ARCHIVED".equals(plan.status())) {
            return;
        }
        jdbc.update("UPDATE study_plan SET status = 'ARCHIVED', archived_at = now() WHERE id = ?",
                planId);
    }

    @Transactional(readOnly = true)
    public PlanRow requireOwned(UUID userId, UUID planId) {
        List<PlanRow> rows = jdbc.query(
                "SELECT * FROM study_plan WHERE id = ? AND user_id = ?", PLAN_MAPPER, planId, userId);
        if (rows.isEmpty()) {
            throw new PlanException("PLAN_NOT_FOUND", "plan not found");
        }
        return rows.get(0);
    }

    /**
     * The plan the learner is currently on.
     *
     * <p>A plan whose days are all finished is {@code COMPLETED}, and it stays
     * current until the exam is behind them: the week between finishing the
     * schedule and sitting the exam is exactly when the Final Review Exam is for,
     * and treating that learner as having no plan would put the one feature they
     * still need out of reach.
     *
     * <p>An in-progress plan always wins, so starting a new one after an exam
     * behaves as before.
     */
    @Transactional(readOnly = true)
    public PlanRow activePlan(UUID userId) {
        List<PlanRow> rows = jdbc.query(
                "SELECT * FROM study_plan WHERE user_id = ? "
                        + "   AND (status IN ('DRAFT','ANALYZING','READY_FOR_REVIEW','ACTIVE') "
                        + "        OR (status = 'COMPLETED' AND exam_date >= CURRENT_DATE)) "
                        + " ORDER BY (status = 'COMPLETED'), created_at DESC LIMIT 1",
                PLAN_MAPPER, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public PlanRow load(UUID planId) {
        return jdbc.query("SELECT * FROM study_plan WHERE id = ?", PLAN_MAPPER, planId)
                .stream().findFirst()
                .orElseThrow(() -> new PlanException("PLAN_NOT_FOUND", "plan not found"));
    }

    // -------------------------------------------------------------- helpers

    /** Accepts day names in any case and clamps to a humane daily maximum. */
    static Map<String, Integer> normaliseCapacity(Map<String, Integer> input) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            out.put(day.name(), 0);
        }
        if (input != null) {
            input.forEach((key, value) -> {
                if (key == null || value == null) {
                    return;
                }
                try {
                    DayOfWeek day = DayOfWeek.valueOf(key.toUpperCase(Locale.ROOT));
                    // 12 hours a day is already unrealistic; beyond that the plan
                    // is fiction and the learner is being flattered.
                    out.put(day.name(), Math.max(0, Math.min(720, value)));
                } catch (IllegalArgumentException ignored) {
                    // Unknown day names are dropped rather than failing the request.
                }
            });
        }
        return out;
    }

    static Map<DayOfWeek, Integer> toDayOfWeekMap(Map<String, Integer> capacity) {
        Map<DayOfWeek, Integer> out = new EnumMap<>(DayOfWeek.class);
        capacity.forEach((key, value) -> out.put(DayOfWeek.valueOf(key), value));
        return out;
    }

    private static LocalDate maxOf(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static final org.springframework.jdbc.core.RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> {
        Map<String, Integer> capacity = Json.read(rs.getString("daily_capacity"),
                new TypeReference<Map<String, Integer>>() { });
        List<String> blocked = Json.read(rs.getString("blocked_dates"),
                new TypeReference<List<String>>() { });
        Set<LocalDate> blockedDates = new HashSet<>();
        blocked.forEach(d -> blockedDates.add(LocalDate.parse(d)));

        return new PlanRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("certification_version_id", UUID.class),
                rs.getObject("material_revision_id", UUID.class),
                rs.getDate("exam_date").toLocalDate(),
                rs.getDate("start_date").toLocalDate(),
                rs.getString("status"),
                toDayOfWeekMap(capacity),
                blockedDates,
                rs.getString("compression_mode"),
                rs.getString("mapping_version"));
    };

    /**
     * One row of the plan list. Flat on purpose: this feeds a list screen, and a
     * nested certification object would only be unwrapped again by every client.
     *
     * @param progressPercent completed study days over total, 0 before activation
     * @param daysUntilExam   0 once the exam date is behind the learner
     */
    public record PlanSummary(
            UUID id,
            String status,
            String examDate,
            String startDate,
            String examCode,
            String versionLabel,
            String certificationName,
            String provider,
            int totalDays,
            int completedDays,
            int progressPercent,
            int daysUntilExam,
            boolean hasMaterial) {
    }

    public record PlanRow(
            UUID id,
            UUID userId,
            UUID certificationVersionId,
            UUID materialRevisionId,
            LocalDate examDate,
            LocalDate startDate,
            String status,
            Map<DayOfWeek, Integer> weeklyCapacity,
            Set<LocalDate> blockedDates,
            String compressionMode,
            String mappingVersion) {
    }

    public static class PlanException extends RuntimeException {
        private final String code;

        public PlanException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
