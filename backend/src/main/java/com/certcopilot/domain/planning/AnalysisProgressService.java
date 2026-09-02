package com.certcopilot.domain.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.shared.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the waiting screen is allowed to say while a plan is being built.
 *
 * <p>The pipeline takes tens of seconds and runs five distinct stages. A spinner
 * with a made-up percentage would be a lie the code has to maintain; naming the
 * stages costs nothing and tells the learner both what is happening and, when it
 * breaks, where.
 *
 * <p>Every stage state is derived from state that actually exists - the revision
 * status, and whether the rows that stage produces are there. That matters for
 * failure: {@code material_revision.status} only says FAILED, so the stage that
 * failed is identified by which artefacts made it to the database rather than by
 * a progress field somebody has to remember to update.
 */
@Service
public class AnalysisProgressService {

    /** Stage identifiers, in pipeline order. The client owns the wording. */
    public static final String UPLOAD = "UPLOAD";
    public static final String EXTRACT = "EXTRACT";
    public static final String STRUCTURE = "STRUCTURE";
    public static final String MAPPING = "MAPPING";
    public static final String SCHEDULE = "SCHEDULE";

    private static final List<String> ORDER = List.of(UPLOAD, EXTRACT, STRUCTURE, MAPPING, SCHEDULE);

    private final JdbcTemplate jdbc;
    private final PlanService plans;

    public AnalysisProgressService(JdbcTemplate jdbc, PlanService plans) {
        this.jdbc = jdbc;
        this.plans = plans;
    }

    @Transactional(readOnly = true)
    public View progress(UUID userId, UUID planId) {
        PlanService.PlanRow plan = plans.requireOwned(userId, planId);
        UUID revisionId = plan.materialRevisionId();

        if (revisionId == null) {
            return new View(plan.status(), "NONE", Map.of(), "", List.of(), 0,
                    stagesBeforeUpload());
        }

        Map<String, Object> revision = jdbc.queryForMap(
                "SELECT status, quality_flags, failure_reason FROM material_revision WHERE id = ?",
                revisionId);
        String materialStatus = String.valueOf(revision.get("status"));
        String failureReason = revision.get("failure_reason") == null
                ? "" : String.valueOf(revision.get("failure_reason"));

        List<Map<String, Object>> sections = jdbc.queryForList(
                "SELECT title, page_start, page_end FROM course_section "
                        + " WHERE material_revision_id = ? ORDER BY order_index", revisionId);
        int unitCount = count("SELECT count(*) FROM learning_unit WHERE material_revision_id = ?",
                revisionId);
        int pageCount = count("SELECT count(*) FROM material_page WHERE material_revision_id = ?",
                revisionId);
        int mappedUnits = count(
                "SELECT count(DISTINCT m.learning_unit_id) FROM unit_exam_mapping m "
                        + "  JOIN learning_unit u ON u.id = m.learning_unit_id "
                        + " WHERE u.material_revision_id = ?", revisionId);

        return new View(plan.status(), materialStatus,
                Json.read(String.valueOf(revision.get("quality_flags")), Map.class),
                failureReason, sections, unitCount,
                stages(plan.status(), materialStatus, pageCount, sections.size(), unitCount,
                        mappedUnits));
    }

    // ----------------------------------------------------------------- stages

    private List<Stage> stagesBeforeUpload() {
        List<Stage> stages = new ArrayList<>();
        for (String key : ORDER) {
            stages.add(new Stage(key, key.equals(UPLOAD) ? "WAITING" : "WAITING", null));
        }
        return stages;
    }

    private List<Stage> stages(String planStatus, String materialStatus,
                               int pageCount, int sectionCount, int unitCount, int mappedUnits) {
        boolean failed = "FAILED".equals(materialStatus);
        boolean extracted = pageCount > 0;
        boolean structured = sectionCount > 0 && unitCount > 0;
        boolean mapped = mappedUnits > 0
                || List.of("READY_FOR_REVIEW", "ACTIVE", "COMPLETED").contains(planStatus);
        boolean scheduled = List.of("ACTIVE", "COMPLETED").contains(planStatus);

        List<Stage> stages = new ArrayList<>();
        stages.add(new Stage(UPLOAD, "DONE", null));

        // On failure, the first stage that produced nothing is the one that broke.
        // Everything after it never ran, and saying so is more useful than five
        // identical error rows.
        stages.add(new Stage(EXTRACT,
                extracted ? "DONE" : failed ? "FAILED" : "IN_PROGRESS",
                extracted ? pageCount + " trang" : null));

        stages.add(new Stage(STRUCTURE,
                structured ? "DONE"
                        : !extracted ? (failed ? "SKIPPED" : "WAITING")
                        : failed ? "FAILED" : "IN_PROGRESS",
                structured ? sectionCount + " phần · " + unitCount + " bài" : null));

        stages.add(new Stage(MAPPING,
                mapped ? "DONE"
                        : !structured ? (failed ? "SKIPPED" : "WAITING")
                        : failed ? "FAILED" : "IN_PROGRESS",
                mapped && mappedUnits > 0 ? mappedUnits + " bài đã đối chiếu" : null));

        stages.add(new Stage(SCHEDULE,
                scheduled ? "DONE" : failed ? "SKIPPED" : mapped ? "WAITING" : "WAITING",
                null));

        return stages;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    /**
     * @param key    stable stage id; the client supplies the label
     * @param state  DONE, IN_PROGRESS, WAITING, SKIPPED or FAILED
     * @param detail measured fact about this stage, or null - never a percentage
     */
    public record Stage(String key, String state, String detail) {
    }

    public record View(String planStatus, String materialStatus,
                       Map<String, Object> qualityFlags, String failureReason,
                       List<Map<String, Object>> sections, int unitCount, List<Stage> stages) {
    }
}
