package com.certcopilot.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.domain.assessment.QuizService;
import com.certcopilot.domain.identity.AuthService;
import com.certcopilot.domain.learning.LearningPackService;
import com.certcopilot.domain.material.MaterialService;
import com.certcopilot.domain.planning.PlanService;
import com.certcopilot.platform.jobs.JobQueue;
import com.certcopilot.platform.jobs.JobRecord;
import com.certcopilot.platform.jobs.JobWorker;
import com.certcopilot.support.PostgresSupport;
import com.certcopilot.support.SyntheticDeck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the D12 evaluation and writes a report.
 *
 * <pre>
 * ./mvnw verify -Dit.test=EvaluationRunnerIT -Deval.level=SMOKE
 * ./mvnw verify -Dit.test=EvaluationRunnerIT -Deval.level=RELEASE \
 *     -Deval.material=/path/to/deck.pdf \
 *     -Deval.scores=factualCorrectness=4.6,... \
 *     -Deval.findings=criticalFactualErrors=0,...
 * </pre>
 *
 * <p><b>What this can and cannot certify.</b> The automatable gates - provenance,
 * source-span resolution, task anchoring, answer counts, schema validity, domain
 * coverage - are checked here and are real. The 1–5 quality scores are human
 * judgement and must be supplied by a reviewer via {@code -Deval.scores=...};
 * without them a RELEASE run fails rather than passing silently.
 *
 * <p>A run against the synthetic deck exercises the harness. It does <em>not</em>
 * constitute a passing Release Eval: that requires real course material and a
 * Vietnamese reviewer, and no amount of fixture data substitutes for it.
 */
class EvaluationRunnerIT extends PostgresSupport {

    private static final UUID AIF_C01 = UUID.fromString("a1f00000-0000-4000-8000-000000000002");

    @Autowired private AuthService auth;
    @Autowired private PlanService plans;
    @Autowired private MaterialService materials;
    @Autowired private LearningPackService packs;
    @Autowired private QuizService quizzes;
    @Autowired private JobQueue jobs;
    @Autowired private JobWorker worker;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.certcopilot.platform.ai.AiProvenance provenance;
    @Autowired private com.certcopilot.platform.ai.AiProperties aiProperties;

    @BeforeEach
    void setUp() {
        requireDatabase();
    }

    @Test
    @DisplayName("evaluation harness runs and produces a report")
    @EnabledIfSystemProperty(named = "eval.level", matches = "SMOKE|RELEASE")
    void runEvaluation() throws Exception {
        EvaluationHarness.Level level =
                EvaluationHarness.Level.valueOf(System.getProperty("eval.level"));

        byte[] deck = loadMaterial();
        boolean syntheticMaterial = System.getProperty("eval.material") == null;

        UUID userId = auth.register("eval-" + UUID.randomUUID() + "@example.com",
                "correct-horse-battery", "Evaluator").userId();
        Map<String, Integer> capacity = new LinkedHashMap<>();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            capacity.put(day.name(), 240);
        }
        UUID planId = plans.createDraft(userId, AIF_C01,
                LocalDate.now().plusDays(60), capacity, List.of(), null);

        materials.upload(userId, planId, "eval-material.pdf", "application/pdf", deck);
        drainJobs();

        List<UUID> unitIds = jdbc.queryForList(
                "SELECT learning_unit_id FROM plan_unit WHERE plan_id = ? ORDER BY order_index",
                UUID.class, planId);

        for (UUID unitId : unitIds) {
            packs.generate(planId, unitId);
            quizzes.generateForUnit(planId, unitId);
        }

        // ---- collect samples --------------------------------------------------
        Map<String, String> sources = new HashMap<>();
        List<EvaluationHarness.PackSample> packSamples = new ArrayList<>();
        List<EvaluationHarness.QuestionSample> questionSamples = new ArrayList<>();

        for (UUID unitId : unitIds) {
            sources.put(unitId.toString(), sourceTextFor(unitId));

            packs.findExisting(planId, unitId).ifPresent(pack -> {
                packSamples.add(new EvaluationHarness.PackSample(
                        unitId.toString(), toPayload(pack), !pack.blocks().isEmpty()));
            });
        }

        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT learning_unit_id, question_type, task_statement_id, source_span, "
                        + "       jsonb_array_length(correct_option_ids) AS correct_count "
                        + "  FROM question WHERE plan_id = ? AND validation_status = 'VALID'",
                planId)) {
            questionSamples.add(new EvaluationHarness.QuestionSample(
                    String.valueOf(row.get("learning_unit_id")),
                    String.valueOf(row.get("question_type")),
                    String.valueOf(row.get("task_statement_id")),
                    (String) row.get("source_span"),
                    ((Number) row.get("correct_count")).intValue()));
        }

        List<String> domains = jdbc.queryForList(
                "SELECT DISTINCT d.code FROM question q "
                        + "  JOIN task_statement t ON t.id = q.task_statement_id "
                        + "  JOIN exam_domain d ON d.id = t.exam_domain_id "
                        + " WHERE q.plan_id = ?", String.class, planId);

        EvaluationHarness.Report report = EvaluationHarness.evaluate(
                level, packSamples, questionSamples, sources, domains,
                readHumanScores(), readReviewerFindings(), costSummary(planId, unitIds.size()));

        String rendered = report.render()
                + (syntheticMaterial
                        ? "\n  MATERIAL: synthetic fixture. This run exercises the harness and the\n"
                          + "  automatable gates only. It is NOT a passing Release Eval - that needs\n"
                          + "  real course material and a Vietnamese reviewer.\n"
                        : "\n  MATERIAL: real, supplied via -Deval.material\n");

        System.out.println(rendered);
        Path out = Path.of("target/eval-" + level + "-"
                + LocalDateTime.now().toString().replace(':', '-') + ".txt");
        Files.writeString(out, rendered);
        System.out.println("report written to " + out.toAbsolutePath());

        // The automatable gates must hold regardless of who supplied the material.
        assertThat(report.hardFailures())
                .as("automatable D12 gates must pass")
                .isEmpty();
    }

    private byte[] loadMaterial() throws Exception {
        String path = System.getProperty("eval.material");
        if (path != null) {
            return Files.readAllBytes(Path.of(path));
        }
        // Larger deck so a release-sized sample is at least structurally possible.
        return SyntheticDeck.buildLarge(
                "RELEASE".equals(System.getProperty("eval.level")) ? 5 : 3);
    }

    /** Scores are supplied as {@code name=value} pairs; absent means unreviewed. */
    private EvaluationHarness.HumanScores readHumanScores() {
        String raw = System.getProperty("eval.scores");
        if (raw == null || raw.isBlank()) {
            return EvaluationHarness.HumanScores.unreviewed();
        }
        Map<String, Double> values = new HashMap<>();
        for (String pair : raw.split(",")) {
            String[] parts = pair.split("=");
            if (parts.length == 2) {
                values.put(parts[0].strip(), Double.parseDouble(parts[1].strip()));
            }
        }
        return new EvaluationHarness.HumanScores(
                values.get("factualCorrectness"),
                values.get("grounding"),
                values.get("examRelevance"),
                values.get("vietnameseClarity"),
                values.get("explanationUsefulness"),
                values.get("quizQuality"),
                values.get("shareAcceptedWithoutRewrite"));
    }

    /**
     * Defect counts supplied as {@code name=value} pairs. Absent means not looked
     * at, which a release run treats as a failed gate rather than a zero.
     */
    private EvaluationHarness.ReviewerFindings readReviewerFindings() {
        Map<String, Integer> values = new HashMap<>();
        String raw = System.getProperty("eval.findings");
        if (raw == null || raw.isBlank()) {
            return EvaluationHarness.ReviewerFindings.unreviewed();
        }
        for (String pair : raw.split(",")) {
            String[] parts = pair.split("=");
            if (parts.length == 2) {
                values.put(parts[0].strip(), Integer.parseInt(parts[1].strip()));
            }
        }
        return new EvaluationHarness.ReviewerFindings(
                values.get("criticalFactualErrors"),
                values.get("hallucinatedSourceSpans"),
                values.get("quizWithWrongAcceptedAnswer"),
                values.get("quizWithUnintendedMultipleCorrect"),
                values.get("conflictsResolvedAgainstOfficial"));
    }

    /**
     * Measured spend for this run, straight from the cost ledger.
     *
     * <p>Reported alongside quality on purpose: a lesson set that passes every
     * quality gate at four times the budget has not passed, and finding that out
     * at release time rather than after launch is the entire point of measuring.
     */
    private EvaluationHarness.CostSummary costSummary(UUID planId, int unitCount) {
        List<EvaluationHarness.OperationCost> byOperation = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT operation_id, "
                        + "       COUNT(*) FILTER (WHERE NOT cache_hit)          AS calls, "
                        + "       COUNT(*) FILTER (WHERE outcome = 'SUCCESS')    AS successes, "
                        + "       COUNT(*) FILTER (WHERE outcome IN ('PARSE_FAIL','SCHEMA_FAIL',"
                        + "                                          'DOMAIN_FAIL','PROVIDER_ERROR')) AS rejected, "
                        + "       COUNT(*) FILTER (WHERE cache_hit)              AS cache_hits, "
                        + "       COALESCE(SUM(tokens_in), 0)                    AS tokens_in, "
                        + "       COALESCE(SUM(tokens_out), 0)                   AS tokens_out, "
                        + "       COALESCE(SUM(cost_cents), 0)                   AS cost_cents "
                        + "  FROM ai_call_ledger WHERE plan_id = ? "
                        + " GROUP BY operation_id ORDER BY operation_id", planId)) {
            byOperation.add(new EvaluationHarness.OperationCost(
                    String.valueOf(row.get("operation_id")),
                    intOf(row, "calls"), intOf(row, "successes"), intOf(row, "rejected"),
                    intOf(row, "cache_hits"), intOf(row, "tokens_in"),
                    intOf(row, "tokens_out"), intOf(row, "cost_cents")));
        }

        Map<String, Object> totals = jdbc.queryForMap(
                "SELECT COALESCE(SUM(cost_cents), 0) AS total, "
                        + "       COALESCE(SUM(cost_cents) FILTER (WHERE outcome NOT IN "
                        + "               ('SUCCESS','CACHE_HIT','BUDGET_DENIED')), 0) AS wasted, "
                        + "       COUNT(*) FILTER (WHERE cache_hit)   AS cache_hits, "
                        + "       COALESCE(SUM(tokens_in), 0)         AS tokens_in, "
                        + "       COALESCE(SUM(tokens_out), 0)        AS tokens_out "
                        + "  FROM ai_call_ledger WHERE plan_id = ?", planId);

        // A price table that has no entry for the model actually used produces an
        // estimate, not a measurement, and the report must not blur the two.
        List<String> models = jdbc.queryForList(
                "SELECT DISTINCT model FROM ai_call_ledger WHERE plan_id = ? AND model <> '-'",
                String.class, planId);
        boolean published = !models.isEmpty()
                && models.stream().allMatch(aiProperties::hasPublishedPrice);

        Integer studyDays = jdbc.queryForObject(
                "SELECT count(*) FROM study_day WHERE plan_id = ?", Integer.class, planId);

        return new EvaluationHarness.CostSummary(
                provenance.forPlan(planId).name(),
                published,
                intOf(totals, "total"),
                intOf(totals, "wasted"),
                intOf(totals, "cache_hits"),
                intOf(totals, "tokens_in"),
                intOf(totals, "tokens_out"),
                unitCount,
                1,
                studyDays == null ? 0 : studyDays,
                byOperation);
    }

    private static int intOf(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? 0 : ((Number) value).intValue();
    }

    private String toPayload(LearningPackService.PackView pack) {
        List<Map<String, Object>> blocks = pack.blocks().stream()
                .map(b -> {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("type", b.type());
                    node.put("origin", b.origin());
                    node.put("payload", b.payload());
                    if (b.sourcePageStart() != null) {
                        node.put("sourcePageStart", b.sourcePageStart());
                    }
                    if (b.sourceSpan() != null) {
                        node.put("sourceSpan", b.sourceSpan());
                    }
                    return node;
                })
                .toList();
        return com.certcopilot.shared.Json.write(Map.of("blocks", blocks));
    }

    private String sourceTextFor(UUID unitId) {
        List<String> parts = jdbc.queryForList(
                "SELECT p.text FROM material_page p "
                        + "  JOIN learning_unit u ON u.material_revision_id = p.material_revision_id "
                        + " WHERE u.id = ? AND p.page_no BETWEEN u.page_start AND u.page_end "
                        + " ORDER BY p.page_no", String.class, unitId);
        return String.join("\n", parts);
    }

    private void drainJobs() {
        for (int round = 0; round < 40; round++) {
            List<JobRecord> claimed = jobs.claim("eval", 20, java.time.Duration.ofMinutes(5));
            if (claimed.isEmpty()) {
                return;
            }
            claimed.forEach(worker::run);
        }
    }
}
