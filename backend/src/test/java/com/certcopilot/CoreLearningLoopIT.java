package com.certcopilot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.domain.assessment.FinalReviewExamService;
import com.certcopilot.domain.assessment.QuizService;
import com.certcopilot.domain.identity.AuthService;
import com.certcopilot.domain.learning.LearningPackService;
import com.certcopilot.domain.material.MaterialService;
import com.certcopilot.domain.planning.CapacityCalculator;
import com.certcopilot.domain.planning.DayCompletionService;
import com.certcopilot.domain.planning.PlanService;
import com.certcopilot.domain.planning.SchedulingService;
import com.certcopilot.domain.planning.TodayService;
import com.certcopilot.domain.planning.internal.scheduler.SchedulingResult;
import com.certcopilot.platform.ai.AiCallLedger;
import com.certcopilot.platform.jobs.JobQueue;
import com.certcopilot.platform.jobs.JobRecord;
import com.certcopilot.platform.jobs.JobWorker;
import com.certcopilot.support.PostgresSupport;
import com.certcopilot.support.SyntheticDeck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core learning loop, end to end, against a real PostgreSQL.
 *
 * <p>This is the test that decides whether the product exists: register, plan,
 * upload, process, map, schedule, study, quiz, grade, detect weakness, replan,
 * and assemble a final review exam. If it passes, the loop the whole design is
 * built around actually closes.
 *
 * <p>Runs entirely offline - the deterministic fake model adapter stands in for a
 * provider, so CI never makes a network call.
 */
class CoreLearningLoopIT extends PostgresSupport {

    private static final UUID AIF_C01 = UUID.fromString("a1f00000-0000-4000-8000-000000000002");

    @Autowired private AuthService auth;
    @Autowired private PlanService plans;
    @Autowired private MaterialService materials;
    @Autowired private SchedulingService scheduling;
    @Autowired private TodayService today;
    @Autowired private DayCompletionService dayCompletion;
    @Autowired private LearningPackService packs;
    @Autowired private QuizService quizzes;
    @Autowired private FinalReviewExamService finalExam;
    @Autowired private JobQueue jobs;
    @Autowired private JobWorker worker;
    @Autowired private AiCallLedger ledger;
    @Autowired private JdbcTemplate jdbc;

    private UUID userId;

    @BeforeEach
    void setUp() {
        requireDatabase();
        String email = "learner-" + UUID.randomUUID() + "@example.com";
        userId = auth.register(email, "correct-horse-battery", "Test Learner").userId();
    }

    @Test
    @DisplayName("a learner goes from signup to a graded quiz and an adjusted schedule")
    void fullLoop() throws Exception {
        // ---- 1. capacity reality check, before any upload --------------------
        LocalDate examDate = LocalDate.now().plusDays(21);
        Map<String, Integer> capacity = weekdayCapacity(180);

        CapacityCalculator.Result preview = plans.previewCapacity(
                AIF_C01, examDate, capacity, List.of());
        assertThat(preview.studyDays()).isPositive();
        assertThat(preview.totalCapacityMinutes()).isPositive();
        assertThat(preview.advice()).isNotEmpty();

        // ---- 2. create the plan ---------------------------------------------
        UUID planId = plans.createDraft(userId, AIF_C01, examDate, capacity, List.of(), null);
        assertThat(plans.activePlan(userId).status()).isEqualTo("DRAFT");

        // ---- 3. upload real PDF bytes and process them -----------------------
        byte[] deck = SyntheticDeck.build(2);
        MaterialService.UploadResult upload = materials.upload(
                userId, planId, "aif-c01-course.pdf", "application/pdf", deck);
        assertThat(upload.materialRevisionId()).isNotNull();

        drainJobs();

        Map<String, Object> revision = jdbc.queryForMap(
                "SELECT status, quality_flags FROM material_revision WHERE id = ?",
                upload.materialRevisionId());
        assertThat(revision.get("status"))
                .as("material must finish processing; flags=%s", revision.get("quality_flags"))
                .isEqualTo("READY");

        // Correction P4: the pipeline must know which pages are mostly visual
        // rather than pretending the extracted text represents them.
        Integer imageDominant = jdbc.queryForObject(
                "SELECT count(*) FROM material_page WHERE material_revision_id = ? "
                        + "   AND page_class = 'IMAGE_DOMINANT'",
                Integer.class, upload.materialRevisionId());
        assertThat(imageDominant)
                .as("diagram slides must be classified, not silently treated as text")
                .isPositive();
        Integer withVisual = jdbc.queryForObject(
                "SELECT count(*) FROM material_page WHERE material_revision_id = ? "
                        + "   AND has_significant_visual",
                Integer.class, upload.materialRevisionId());
        assertThat(withVisual).isPositive();

        // ---- 4. structure and units -----------------------------------------
        Integer sections = jdbc.queryForObject(
                "SELECT count(*) FROM course_section WHERE material_revision_id = ?",
                Integer.class, upload.materialRevisionId());
        assertThat(sections).isGreaterThanOrEqualTo(2);

        List<Map<String, Object>> units = jdbc.queryForList(
                "SELECT id, page_start, page_end, base_effort_minutes FROM learning_unit "
                        + " WHERE material_revision_id = ? ORDER BY sequence",
                upload.materialRevisionId());
        assertThat(units).isNotEmpty();
        assertThat(units).allSatisfy(u ->
                assertThat(((Number) u.get("base_effort_minutes")).intValue()).isPositive());

        // ---- 5. exam mapping and coverage ------------------------------------
        assertThat(plans.load(planId).status()).isEqualTo("READY_FOR_REVIEW");

        Integer mappings = jdbc.queryForObject(
                "SELECT count(*) FROM unit_exam_mapping m JOIN learning_unit u "
                        + "    ON u.id = m.learning_unit_id "
                        + " WHERE u.material_revision_id = ?",
                Integer.class, upload.materialRevisionId());
        assertThat(mappings).as("units must be mapped to the exam scope").isPositive();

        Integer coverage = jdbc.queryForObject(
                "SELECT count(*) FROM coverage_assessment WHERE material_revision_id = ?",
                Integer.class, upload.materialRevisionId());
        assertThat(coverage).as("coverage is an MVP capability, not just a screen").isPositive();

        Integer planUnits = jdbc.queryForObject(
                "SELECT count(*) FROM plan_unit WHERE plan_id = ?", Integer.class, planId);
        assertThat(planUnits).isEqualTo(units.size());

        // ---- 6. schedule ------------------------------------------------------
        SchedulingResult result = scheduling.reschedule(planId, "INITIAL", List.of());
        assertThat(result).isInstanceOf(SchedulingResult.Scheduled.class);
        SchedulingResult.Scheduled scheduled = (SchedulingResult.Scheduled) result;
        assertThat(scheduled.days()).isNotEmpty();
        assertThat(scheduled.days())
                .as("nothing may be scheduled on or after the exam date")
                .allSatisfy(d -> assertThat(d.date()).isBefore(examDate));

        jdbc.update("UPDATE study_plan SET status = 'ACTIVE', activated_at = now() WHERE id = ?", planId);
        dayCompletion.enqueueUpcomingContent(planId);
        drainJobs();

        // ---- 7. today ---------------------------------------------------------
        TodayService.TodayView view = today.today(userId);
        assertThat(view.hasPlan()).isTrue();
        assertThat(view.planStatus()).isEqualTo("ACTIVE");
        assertThat(view.items()).isNotEmpty();
        assertThat(view.daysUntilExam()).isPositive();

        TodayService.TodayItem first = view.items().stream()
                .filter(i -> i.learningUnitId() != null)
                .findFirst().orElseThrow();

        // ---- 8. the Vietnamese learning pack ----------------------------------
        LearningPackService.PackView pack = packs.findExisting(planId, first.learningUnitId())
                .orElseGet(() -> packs.generate(planId, first.learningUnitId()).orElseThrow());
        assertThat(pack.blocks()).isNotEmpty();

        // Companion positioning (D1): material-origin blocks must be traceable.
        assertThat(pack.blocks().stream().filter(b -> "FROM_MATERIAL".equals(b.origin())))
                .as("a block claiming material origin must carry a page range")
                .allSatisfy(b -> assertThat(b.sourcePageStart()).isNotNull());
        assertThat(pack.unit().pageStart()).isNotNull();
        assertThat(pack.unit().materialId()).isNotNull();

        // ---- 9. quiz and deterministic grading --------------------------------
        Integer banked = jdbc.queryForObject(
                "SELECT count(*) FROM question WHERE plan_id = ? AND validation_status = 'VALID'",
                Integer.class, planId);
        assertThat(banked).as("questions must reach the bank").isPositive();

        // Every stored question is grounded: this is the hard gate.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM question WHERE plan_id = ? "
                        + "   AND (source_span IS NULL OR length(trim(source_span)) = 0)",
                Integer.class, planId))
                .as("a question without a source span must never be stored")
                .isZero();

        QuizService.AttemptView attempt = quizzes.startDailyQuiz(planId, view.studyDayId());
        assertThat(attempt.questions()).isNotEmpty();

        // Answer deliberately: first half right, rest wrong, so weakness is real.
        int index = 0;
        for (QuizService.QuestionView question : attempt.questions()) {
            List<String> answer = index < attempt.questions().size() / 2
                    ? correctAnswerFor(question.id())
                    : List.of(wrongOptionFor(question));
            quizzes.answer(planId, attempt.id(), question.id(), answer);
            index++;
        }

        QuizService.AttemptView graded = quizzes.submit(planId, attempt.id());
        assertThat(graded.status()).isEqualTo("GRADED");
        assertThat(graded.scoreRaw()).isNotNull();
        assertThat(graded.scoreTotal()).isEqualTo(attempt.questions().size());
        assertThat(graded.questions())
                .as("a graded attempt must explain every answer")
                .allSatisfy(q -> assertThat(q.explanation()).isNotBlank());

        // ---- 10. mastery ------------------------------------------------------
        Integer mastery = jdbc.queryForObject(
                "SELECT count(*) FROM topic_mastery WHERE plan_id = ?", Integer.class, planId);
        assertThat(mastery).as("mastery must be derived from answers").isPositive();

        // ---- 11. complete the day, which closes the loop ----------------------
        DayCompletionService.CompletionResult completion =
                dayCompletion.completeDay(userId, planId, view.studyDayId());
        assertThat(completion.completedDayId()).isEqualTo(view.studyDayId());
        assertThat(completion.scheduleInfeasible()).isFalse();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM study_day WHERE id = ?", String.class, view.studyDayId()))
                .isEqualTo("COMPLETED");

        // The completed day must survive the replan untouched.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM study_day WHERE plan_id = ? AND status = 'COMPLETED'",
                Integer.class, planId)).isEqualTo(1);

        Integer adjustments = jdbc.queryForObject(
                "SELECT count(*) FROM plan_adjustment WHERE plan_id = ?", Integer.class, planId);
        assertThat(adjustments).as("every replan must leave an auditable record").isPositive();

        // ---- 12. tomorrow is ready --------------------------------------------
        drainJobs();
        TodayService.TodayView tomorrow = today.today(userId);
        assertThat(tomorrow.studyDayId())
                .as("the next day must exist and differ from the completed one")
                .isNotEqualTo(view.studyDayId());

        // ---- 13. progress ------------------------------------------------------
        TodayService.ProgressView progress = today.progress(userId);
        assertThat(progress.completedDays()).isEqualTo(1);
        assertThat(progress.domains()).isNotEmpty();
        assertThat(progress.coverage()).isNotNull();
        assertThat(progress.coverage().domains()).isNotEmpty();

        // ---- 14. cost was actually accounted for --------------------------------
        assertThat(ledger.totalCostCents(planId))
                .as("every provider attempt must be billed, including rejected ones")
                .isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT reserved_cents FROM plan_budget WHERE plan_id = ?", Integer.class, planId))
                .as("no budget reservation may be left stranded")
                .isZero();
    }

    @Test
    @DisplayName("the final review exam is assembled from the validated bank, weighted by domain")
    void finalReviewExam() throws Exception {
        LocalDate examDate = LocalDate.now().plusDays(30);
        UUID planId = plans.createDraft(userId, AIF_C01, examDate, weekdayCapacity(240), List.of(), null);

        materials.upload(userId, planId, "aif-c01-course.pdf", "application/pdf",
                SyntheticDeck.build(3));
        drainJobs();

        scheduling.reschedule(planId, "INITIAL", List.of());
        jdbc.update("UPDATE study_plan SET status = 'ACTIVE' WHERE id = ?", planId);

        // Fill the bank across the whole deck so a weighted draw is possible.
        for (UUID unitId : jdbc.queryForList(
                "SELECT learning_unit_id FROM plan_unit WHERE plan_id = ?", UUID.class, planId)) {
            quizzes.generateForUnit(planId, unitId);
        }

        FinalReviewExamService.Readiness readiness = finalExam.readiness(planId);
        assertThat(readiness.ready())
                .as("bank has %d questions, needs %d", readiness.bankSize(), readiness.minimumRequired())
                .isTrue();

        QuizService.AttemptView exam = finalExam.start(planId, 20);
        assertThat(exam.kind()).isEqualTo("FINAL_REVIEW");
        assertThat(exam.questions()).isNotEmpty();

        for (QuizService.QuestionView question : exam.questions()) {
            quizzes.answer(planId, exam.id(), question.id(), correctAnswerFor(question.id()));
        }
        quizzes.submit(planId, exam.id());

        FinalReviewExamService.ExamResult result = finalExam.result(planId, exam.id());
        assertThat(result.total()).isEqualTo(exam.questions().size());
        assertThat(result.correct())
                .as("answering every question correctly must score full marks")
                .isEqualTo(result.total());
        assertThat(result.byDomain())
                .as("the result must break down by exam domain")
                .isNotEmpty();
    }

    @Test
    @DisplayName("an impossible deadline is refused with options instead of a fake schedule")
    void infeasibleScheduleIsRefused() throws Exception {
        // One hour a day, two days, against a whole course.
        LocalDate examDate = LocalDate.now().plusDays(2);
        UUID planId = plans.createDraft(userId, AIF_C01, examDate, weekdayCapacity(20), List.of(), null);

        materials.upload(userId, planId, "aif-c01-course.pdf", "application/pdf",
                SyntheticDeck.build(1));
        drainJobs();

        SchedulingResult result = scheduling.reschedule(planId, "INITIAL", List.of());

        if (result instanceof SchedulingResult.Infeasible infeasible) {
            assertThat(infeasible.suggestions())
                    .as("refusing without options is useless to a learner")
                    .isNotEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM study_day WHERE plan_id = ?", Integer.class, planId))
                    .as("an invalid schedule must never be persisted")
                    .isZero();
        } else {
            // If it did fit, the compression ladder must have visibly given something up.
            SchedulingResult.Scheduled scheduled = (SchedulingResult.Scheduled) result;
            assertThat(scheduled.droppedUnits())
                    .as("a two-day plan for a full course must drop content")
                    .isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Runs every due job to completion, the way the worker would. */
    @Test
    @DisplayName("finishing every day leaves the final review exam reachable, not the empty state")
    void aFinishedPlanStillOffersTheFinalExam() throws Exception {
        UUID planId = plans.createDraft(userId, AIF_C01, LocalDate.now().plusDays(20),
                weekdayCapacity(240), List.of(), null);
        materials.upload(userId, planId, "aif-c01-course.pdf", "application/pdf",
                SyntheticDeck.build(3));
        drainJobs();
        scheduling.reschedule(planId, "INITIAL", List.of());
        jdbc.update("UPDATE study_plan SET status = 'ACTIVE' WHERE id = ?", planId);

        for (UUID unitId : jdbc.queryForList(
                "SELECT learning_unit_id FROM plan_unit WHERE plan_id = ?", UUID.class, planId)) {
            quizzes.generateForUnit(planId, unitId);
        }

        // The learner finishes the schedule, which is when the plan is marked
        // COMPLETED and when the final review exam becomes the point of the product.
        jdbc.update("UPDATE study_day SET status = 'COMPLETED', completed_at = now() "
                + " WHERE plan_id = ?", planId);
        jdbc.update("UPDATE study_plan SET status = 'COMPLETED', completed_at = now() "
                + " WHERE id = ?", planId);

        TodayService.TodayView view = today.today(userId);

        assertThat(view.hasPlan())
                .as("a finished plan is still the learner's plan until the exam is behind them")
                .isTrue();
        assertThat(view.studyDayId()).as("no day is left to study").isNull();
        assertThat(view.finalExam())
                .as("without readiness the client cannot offer the exam, and this is the "
                        + "only screen that offers it")
                .isNotNull();
        assertThat(view.finalExam().ready()).isTrue();
    }

    @Test
    @DisplayName("a replan rewrites the schedule without regenerating a single artifact")
    void replanningCostsNothing() throws Exception {
        UUID planId = plans.createDraft(userId, AIF_C01, LocalDate.now().plusDays(30),
                weekdayCapacity(240), List.of(), null);
        materials.upload(userId, planId, "aif-c01-course.pdf", "application/pdf",
                SyntheticDeck.build(3));
        drainJobs();

        scheduling.reschedule(planId, "INITIAL", List.of());
        jdbc.update("UPDATE study_plan SET status = 'ACTIVE' WHERE id = ?", planId);

        List<UUID> unitIds = jdbc.queryForList(
                "SELECT learning_unit_id FROM plan_unit WHERE plan_id = ?", UUID.class, planId);
        for (UUID unitId : unitIds) {
            packs.generate(planId, unitId);
            quizzes.generateForUnit(planId, unitId);
        }

        int costBefore = ledger.totalCostCents(planId);
        List<UUID> packsBefore = ids("SELECT id FROM learning_pack WHERE plan_id = ? ORDER BY id", planId);
        List<UUID> questionsBefore = ids("SELECT id FROM question WHERE plan_id = ? ORDER BY id", planId);
        List<String> scheduleBefore = jdbc.queryForList(
                "SELECT day_date::text FROM study_day WHERE plan_id = ? ORDER BY day_index",
                String.class, planId);

        assertThat(costBefore).isPositive();
        assertThat(packsBefore).isNotEmpty();
        assertThat(questionsBefore).isNotEmpty();

        // The learner moves the exam. Every replan trigger runs the same function.
        plans.updateConstraints(userId, planId, LocalDate.now().plusDays(45),
                weekdayCapacity(180), List.of());
        scheduling.reschedule(planId, "CONSTRAINTS_CHANGED", List.of());
        drainJobs();

        List<String> scheduleAfter = jdbc.queryForList(
                "SELECT day_date::text FROM study_day WHERE plan_id = ? ORDER BY day_index",
                String.class, planId);
        assertThat(scheduleAfter)
                .as("the replan must actually change the schedule, or this test proves nothing")
                .isNotEqualTo(scheduleBefore);

        // This is the whole justification for splitting LearningUnit from PlanUnit
        // from StudyDayItem (correction A1). If a replan regenerated content, every
        // missed day would cost real money and the product would punish the learner
        // for falling behind.
        assertThat(ledger.totalCostCents(planId))
                .as("a replan must not call the model even once")
                .isEqualTo(costBefore);
        assertThat(ids("SELECT id FROM learning_pack WHERE plan_id = ? ORDER BY id", planId))
                .as("lessons survive a replan untouched")
                .isEqualTo(packsBefore);
        assertThat(ids("SELECT id FROM question WHERE plan_id = ? ORDER BY id", planId))
                .as("the question bank survives a replan untouched")
                .isEqualTo(questionsBefore);
    }

    private List<UUID> ids(String sql, UUID planId) {
        return jdbc.queryForList(sql, UUID.class, planId);
    }

    private void drainJobs() {
        for (int round = 0; round < 40; round++) {
            List<JobRecord> claimed = jobs.claim(
                    "test-worker", 20, java.time.Duration.ofMinutes(5));
            if (claimed.isEmpty()) {
                return;
            }
            claimed.forEach(worker::run);
        }
    }

    private List<String> correctAnswerFor(UUID questionId) {
        String json = jdbc.queryForObject(
                "SELECT correct_option_ids::text FROM question WHERE id = ?", String.class, questionId);
        return com.certcopilot.shared.Json.read(json,
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
    }

    private String wrongOptionFor(QuizService.QuestionView question) {
        List<String> correct = correctAnswerFor(question.id());
        for (Map<String, Object> option : question.options()) {
            String id = String.valueOf(option.get("id"));
            if (!correct.contains(id)) {
                return id;
            }
        }
        return "A";
    }

    private static Map<String, Integer> weekdayCapacity(int minutes) {
        Map<String, Integer> capacity = new java.util.LinkedHashMap<>();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            capacity.put(day.name(), minutes);
        }
        return capacity;
    }
}
