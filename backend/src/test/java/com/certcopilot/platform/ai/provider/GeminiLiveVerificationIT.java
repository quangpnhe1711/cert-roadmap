package com.certcopilot.platform.ai.provider;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.domain.assessment.QuizService;
import com.certcopilot.domain.assessment.WeakTopicDetector;
import com.certcopilot.domain.assessment.WeaknessExplanationService;
import com.certcopilot.domain.identity.AuthService;
import com.certcopilot.domain.learning.LearningPackService;
import com.certcopilot.domain.material.MaterialService;
import com.certcopilot.domain.planning.PlanService;
import com.certcopilot.domain.planning.SchedulingService;
import com.certcopilot.domain.planning.TodayService;
import com.certcopilot.platform.ai.AiOperations;
import com.certcopilot.platform.ai.LlmPort;
import com.certcopilot.platform.jobs.JobQueue;
import com.certcopilot.platform.jobs.JobRecord;
import com.certcopilot.platform.jobs.JobWorker;
import com.certcopilot.support.PostgresSupport;
import com.certcopilot.support.SyntheticDeck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole product loop against the real Gemini API.
 *
 * <p>Opt-in and never part of a normal build: it spends the account's tokens, and
 * a suite that quietly costs money every time somebody runs {@code mvn verify} is
 * a suite people stop running. Enable it with a credential and an explicit flag:
 *
 * <pre>
 *   GEMINI_API_KEY=... ./mvnw verify -Dgemini.live=true -Dit.test=GeminiLiveVerificationIT
 * </pre>
 *
 * <p>What it proves that the contract test cannot: that the request this adapter
 * builds is one the real API accepts, that the models named in configuration
 * exist and are permitted for this account, that the six prompts survive a real
 * model's JSON mode, and that usage metadata comes back in the shape the ledger
 * assumes.
 *
 * <p><b>There is no fallback.</b> If Gemini fails, this test fails. It never
 * reaches for the deterministic adapter to rescue a run, because a green result
 * produced from fixture output would certify the exact thing it exists to check.
 * The assertion below that no synthetic model appears in the ledger is what makes
 * that structural rather than a promise.
 *
 * <p>The deck is synthetic, so this proves <em>integration</em>. It is not the
 * real-material evaluation, which needs a real AIF-C01 deck and a reviewer.
 */
// Container-level, so this is decided before Spring is asked to build anything.
// An assumption inside @BeforeEach would be too late: the context is created
// first, the adapter refuses to start without a credential, and a normal
// keyless build fails on a test that was meant to be skipped.
@EnabledIfSystemProperty(named = "gemini.live", matches = "true",
        disabledReason = "live Gemini verification is opt-in; run with -Dgemini.live=true")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+",
        disabledReason = "live Gemini verification needs GEMINI_API_KEY in the environment")
@TestPropertySource(properties = {
        "app.ai.adapter=gemini",
        // A live model is slower than the fake by two orders of magnitude, and a
        // whole plan is dozens of calls.
        "app.ai.provider.gemini.request-timeout-ms=180000"
})
class GeminiLiveVerificationIT extends PostgresSupport {

    private static final UUID AIF_C01 = UUID.fromString("a1f00000-0000-4000-8000-000000000002");

    /** Every operation that must be seen answering for real before this passes. */
    private static final List<String> REQUIRED_OPERATIONS = List.of(
            AiOperations.STRUCTURE_EXTRACT,
            AiOperations.TOPICS_EXTRACT,
            AiOperations.TOPIC_TO_TASK,
            AiOperations.PACK_GENERATE,
            AiOperations.QUIZ_GENERATE,
            AiOperations.WEAKNESS_EXPLAIN);

    @Autowired private AuthService auth;
    @Autowired private PlanService plans;
    @Autowired private MaterialService materials;
    @Autowired private SchedulingService scheduling;
    @Autowired private TodayService today;
    @Autowired private LearningPackService packs;
    @Autowired private QuizService quizzes;
    @Autowired private WeakTopicDetector weakTopics;
    @Autowired private WeaknessExplanationService weaknessExplanations;
    @Autowired private LlmPort llm;
    @Autowired private JobQueue jobs;
    @Autowired private JobWorker worker;
    @Autowired private JdbcTemplate jdbc;

    private UUID userId;

    @BeforeEach
    void setUp() {
        requireDatabase();
        userId = auth.register("gemini-live-" + UUID.randomUUID() + "@example.com",
                "correct-horse-battery", "Gemini Live").userId();
    }

    @Test
    @DisplayName("all six AI operations run against the real Gemini API, with usage and cost recorded")
    void allSixOperationsRunLive() throws Exception {
        assertThat(llm)
                .as("the whole point of this test is that the real adapter is wired in")
                .isInstanceOf(GeminiLlmAdapter.class);

        // ---- plan, upload, and the three pipeline operations -----------------
        LocalDate examDate = LocalDate.now().plusDays(21);
        UUID planId = plans.createDraft(userId, AIF_C01, examDate, weekdayCapacity(180),
                List.of(), null);

        MaterialService.UploadResult upload = materials.upload(userId, planId,
                "aif-c01-course.pdf", "application/pdf", SyntheticDeck.build(2));
        drainJobs();

        assertThat(jdbc.queryForObject("SELECT status FROM material_revision WHERE id = ?",
                String.class, upload.materialRevisionId()))
                .as("the pipeline must survive real model output, not just the fake's")
                .isEqualTo("READY");
        assertThat(plans.load(planId).status()).isEqualTo("READY_FOR_REVIEW");

        // ---- activate, which pre-generates lessons and questions -------------
        scheduling.reschedule(planId, "INITIAL", List.of());
        jdbc.update("UPDATE study_plan SET status = 'ACTIVE', activated_at = now() WHERE id = ?",
                planId);

        TodayService.TodayView view = today.today(userId);
        UUID unitId = view.items().stream()
                .filter(i -> i.learningUnitId() != null)
                .findFirst().orElseThrow().learningUnitId();

        LearningPackService.PackView pack = packs.findExisting(planId, unitId)
                .orElseGet(() -> packs.generate(planId, unitId).orElseThrow(
                        () -> new AssertionError("Gemini produced no valid learning pack")));
        assertThat(pack.blocks())
                .as("a lesson with no blocks is a failure the ledger has already paid for")
                .isNotEmpty();

        quizzes.generateForUnit(planId, unitId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM question WHERE plan_id = ? AND validation_status = 'VALID'",
                Integer.class, planId))
                .as("Gemini questions must survive the grounding validator, not bypass it")
                .isPositive();

        // ---- answer wrongly, so the sixth operation has real work to do -------
        answerEverythingWrong(planId, view.studyDayId());
        List<WeakTopicDetector.WeakTopic> weak = weakTopics.detect(planId);
        assertThat(weak)
                .as("deliberately wrong answers must produce weak topics deterministically")
                .isNotEmpty();

        WeaknessExplanationService.Explanation explanation =
                weaknessExplanations.explain(planId, weak.get(0).courseTopicId())
                        .orElseThrow(() -> new AssertionError(
                                "Gemini produced no valid weakness explanation"));
        assertThat(explanation.summary()).isNotBlank();

        // ---- every operation must be on the ledger, with real usage ----------
        Map<String, Usage> usage = ledgerByOperation(planId);
        for (String operationId : REQUIRED_OPERATIONS) {
            Usage row = usage.get(operationId);
            assertThat(row)
                    .as("%s never reached the provider; an operation skipped is an "
                            + "operation unverified", operationId)
                    .isNotNull();
            assertThat(row.model()).as("%s must record the Gemini model that served it", operationId)
                    .startsWith("gemini");
            assertThat(row.tokensIn()).as("%s input tokens", operationId).isPositive();
            assertThat(row.tokensOut()).as("%s output tokens", operationId).isPositive();
        }

        // ---- and no synthetic output may have crept in ------------------------
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_call_ledger WHERE plan_id = ? AND model LIKE ?",
                Integer.class, planId, LlmPort.SYNTHETIC_MODEL_PREFIX + "%"))
                .as("a live run rescued by the fake adapter would certify nothing")
                .isZero();

        assertThat(jdbc.queryForObject(
                "SELECT reserved_cents FROM plan_budget WHERE plan_id = ?", Integer.class, planId))
                .as("no budget reservation may be left stranded after a live run")
                .isZero();

        // ---- the cache must actually stop the second call ---------------------
        // Folded into this run rather than given its own test: a second pipeline
        // would cost real tokens to prove something this plan can already show.
        int providerCallsBefore = providerCalls(planId, AiOperations.PACK_GENERATE);
        int tokensBefore = tokensSpent(planId);

        packs.generate(planId, unitId);

        assertThat(providerCalls(planId, AiOperations.PACK_GENERATE))
                .as("a cache hit that still calls the provider is not a cache")
                .isEqualTo(providerCallsBefore);
        assertThat(tokensSpent(planId))
                .as("a cache hit must cost zero provider tokens")
                .isEqualTo(tokensBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_call_ledger WHERE plan_id = ? AND operation_id = ? "
                        + "   AND outcome = 'CACHE_HIT'",
                Integer.class, planId, AiOperations.PACK_GENERATE))
                .as("the hit itself must be recorded, or cache effectiveness is unmeasurable")
                .isPositive();
    }

    // ------------------------------------------------------------------ helpers

    private void answerEverythingWrong(UUID planId, UUID studyDayId) {
        QuizService.AttemptView attempt = quizzes.startDailyQuiz(planId, studyDayId);
        for (QuizService.QuestionView question : attempt.questions()) {
            quizzes.answer(planId, attempt.id(), question.id(), List.of(wrongOptionFor(question)));
        }
        quizzes.submit(planId, attempt.id());
    }

    private String wrongOptionFor(QuizService.QuestionView question) {
        List<String> correct = jdbc.queryForList(
                "SELECT jsonb_array_elements_text(correct_option_ids) FROM question WHERE id = ?",
                String.class, question.id());
        for (Map<String, Object> option : question.options()) {
            String id = String.valueOf(option.get("id"));
            if (!correct.contains(id)) {
                return id;
            }
        }
        return "A";
    }

    private Map<String, Usage> ledgerByOperation(UUID planId) {
        Map<String, Usage> usage = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT operation_id, model, sum(tokens_in) AS tin, sum(tokens_out) AS tout "
                        + "  FROM ai_call_ledger "
                        + " WHERE plan_id = ? AND outcome = 'SUCCESS' AND cache_hit = false "
                        + " GROUP BY operation_id, model", planId)) {
            usage.put(String.valueOf(row.get("operation_id")), new Usage(
                    String.valueOf(row.get("model")),
                    ((Number) row.get("tin")).intValue(),
                    ((Number) row.get("tout")).intValue()));
        }
        return usage;
    }

    private int providerCalls(UUID planId, String operationId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM ai_call_ledger WHERE plan_id = ? AND operation_id = ? "
                        + "   AND cache_hit = false AND model <> '-'",
                Integer.class, planId, operationId);
        return n == null ? 0 : n;
    }

    private int tokensSpent(UUID planId) {
        Integer n = jdbc.queryForObject(
                "SELECT COALESCE(sum(tokens_in + tokens_out), 0) FROM ai_call_ledger WHERE plan_id = ?",
                Integer.class, planId);
        return n == null ? 0 : n;
    }

    /** Runs every due job to completion, the way the worker would. */
    private void drainJobs() {
        for (int round = 0; round < 60; round++) {
            List<JobRecord> claimed = jobs.claim(
                    "gemini-live-worker", 20, java.time.Duration.ofMinutes(10));
            if (claimed.isEmpty()) {
                return;
            }
            claimed.forEach(worker::run);
        }
    }

    private static Map<String, Integer> weekdayCapacity(int minutes) {
        Map<String, Integer> capacity = new LinkedHashMap<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")) {
            capacity.put(day, minutes);
        }
        capacity.put("SATURDAY", minutes);
        capacity.put("SUNDAY", minutes);
        return capacity;
    }

    private record Usage(String model, int tokensIn, int tokensOut) {
    }
}
