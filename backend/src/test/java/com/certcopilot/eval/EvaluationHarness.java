package com.certcopilot.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Decision D12: the definition of done for AI content quality.
 *
 * <p>Two levels. <b>Smoke</b> runs against 8 representative learning units while
 * a prompt is being iterated on. <b>Release</b> runs against 20–24 units covering
 * every launch exam domain and every materially different content type, plus
 * 80–100 generated questions, and is the gate for shipping.
 *
 * <p>The hard gates below are automatable and are checked here. The 1–5 quality
 * scores are human judgement and are recorded by a reviewer into the same report;
 * this class computes the aggregate and applies the thresholds, but it does not
 * invent the scores. Fabricating an evaluation result would defeat the entire
 * purpose of having one.
 *
 * <p>Prompt freeze requires <em>two consecutive</em> passing release runs.
 */
public final class EvaluationHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Level {
        SMOKE(8, 8, 0),
        RELEASE(20, 24, 80);

        public final int minUnits;
        public final int maxUnits;
        public final int minQuestions;

        Level(int minUnits, int maxUnits, int minQuestions) {
            this.minUnits = minUnits;
            this.maxUnits = maxUnits;
            this.minQuestions = minQuestions;
        }
    }

    /** Human 1–5 scores. Null means not yet reviewed, which fails a release run. */
    public record HumanScores(
            Double factualCorrectness,
            Double grounding,
            Double examRelevance,
            Double vietnameseClarity,
            Double explanationUsefulness,
            Double quizQuality,
            Double shareAcceptedWithoutRewrite) {

        public static HumanScores unreviewed() {
            return new HumanScores(null, null, null, null, null, null, null);
        }
    }

    /**
     * Defect counts a reviewer supplies, each a hard gate at zero.
     *
     * <p>Separate from the 1-5 scores on purpose. An average cannot express
     * "exactly none of these": a lesson set can score 4.6 on factual correctness
     * and still contain one confidently wrong statement, and one confidently
     * wrong statement is what a learner carries into the exam. The 1-5 scale
     * measures how good the good output is; these count what must not exist.
     *
     * <p>All are human judgement, and all are required for a release run: a null
     * is "not looked at", which is not the same as zero.
     */
    public record ReviewerFindings(
            Integer criticalFactualErrors,
            /** Span resolves in the source but does not actually support the claim. */
            Integer hallucinatedSourceSpans,
            Integer quizWithWrongAcceptedAnswer,
            /** More than one option is genuinely correct, whatever the key says. */
            Integer quizWithUnintendedMultipleCorrect,
            /** Material contradicted the official exam guide and the material won. */
            Integer conflictsResolvedAgainstOfficial) {

        public static ReviewerFindings unreviewed() {
            return new ReviewerFindings(null, null, null, null, null);
        }

        List<String> gateNames() {
            return List.of("criticalFactualErrors", "hallucinatedSourceSpans",
                    "quizWithWrongAcceptedAnswer", "quizWithUnintendedMultipleCorrect",
                    "conflictsResolvedAgainstOfficial");
        }

        Integer valueOf(String name) {
            return switch (name) {
                case "criticalFactualErrors" -> criticalFactualErrors;
                case "hallucinatedSourceSpans" -> hallucinatedSourceSpans;
                case "quizWithWrongAcceptedAnswer" -> quizWithWrongAcceptedAnswer;
                case "quizWithUnintendedMultipleCorrect" -> quizWithUnintendedMultipleCorrect;
                case "conflictsResolvedAgainstOfficial" -> conflictsResolvedAgainstOfficial;
                default -> throw new IllegalArgumentException(name);
            };
        }
    }

    /**
     * Measured spend for the run, so a release decision sees quality and cost together.
     *
     * @param pricingIsPublished false when the model's real price is not configured
     *                           and the tier estimate was used; the numbers are then
     *                           indicative and the report says so
     */
    public record CostSummary(String provenance,
                              boolean pricingIsPublished,
                              int totalCents,
                              int retryCents,
                              int cacheHits,
                              int tokensIn,
                              int tokensOut,
                              int units,
                              int materials,
                              int studyDays,
                              List<OperationCost> byOperation) {

        public static CostSummary none() {
            return new CostSummary("NONE", false, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }
    }

    public record OperationCost(String operationId, int calls, int successes, int rejected,
                                int cacheHits, int tokensIn, int tokensOut, int costCents) {
    }

    /** Thresholds fixed by D12. Not adjustable at runtime, by design. */
    public static final Map<String, Double> QUALITY_TARGETS = Map.of(
            "factualCorrectness", 4.5,
            "grounding", 4.5,
            "examRelevance", 4.3,
            "vietnameseClarity", 4.2,
            "explanationUsefulness", 4.2,
            "quizQuality", 4.2,
            "shareAcceptedWithoutRewrite", 0.90);

    private EvaluationHarness() {
    }

    /**
     * Runs the automatable half of the evaluation.
     *
     * @param packs     generated packs, as stored
     * @param questions generated questions, as stored
     * @param sources   source text per unit, for resolving spans
     */
    public static Report evaluate(Level level,
                                  List<PackSample> packs,
                                  List<QuestionSample> questions,
                                  Map<String, String> sources,
                                  List<String> domainsCovered,
                                  HumanScores human,
                                  ReviewerFindings findings,
                                  CostSummary cost) {

        List<String> hardFailures = new ArrayList<>();
        Map<String, Integer> counters = new LinkedHashMap<>();

        // --- sample size --------------------------------------------------
        if (packs.size() < level.minUnits) {
            hardFailures.add("sample too small: %d packs, %s requires at least %d"
                    .formatted(packs.size(), level, level.minUnits));
        }
        if (questions.size() < level.minQuestions) {
            hardFailures.add("sample too small: %d questions, %s requires at least %d"
                    .formatted(questions.size(), level, level.minQuestions));
        }

        // --- provenance: every material-origin block must be traceable -----
        int blocksTotal = 0;
        int blocksWithoutProvenance = 0;
        int unresolvableSpans = 0;

        for (PackSample pack : packs) {
            for (JsonNode block : readBlocks(pack.payload())) {
                blocksTotal++;
                String origin = block.path("origin").asText("FROM_MATERIAL");
                if ("FROM_MATERIAL".equals(origin) && !block.hasNonNull("sourcePageStart")) {
                    blocksWithoutProvenance++;
                }
                String span = block.path("sourceSpan").asText("");
                if (!span.isBlank() && !resolves(span, sources.get(pack.unitId()))) {
                    unresolvableSpans++;
                }
            }
        }
        counters.put("blocks", blocksTotal);

        if (blocksWithoutProvenance > 0) {
            hardFailures.add(blocksWithoutProvenance
                    + " block(s) claim material origin but carry no page reference");
        }
        if (unresolvableSpans > 0) {
            hardFailures.add(unresolvableSpans + " block source span(s) do not resolve in the source");
        }

        // --- questions: the gates that matter most -------------------------
        int missingSpan = 0;
        int unresolvableQuestionSpan = 0;
        int missingTask = 0;
        int wrongAnswerCount = 0;

        for (QuestionSample question : questions) {
            if (question.sourceSpan() == null || question.sourceSpan().isBlank()) {
                missingSpan++;
            } else if (!resolves(question.sourceSpan(), sources.get(question.unitId()))) {
                unresolvableQuestionSpan++;
            }
            if (question.taskStatementId() == null || question.taskStatementId().isBlank()) {
                missingTask++;
            }
            int expected = "MULTIPLE_RESPONSE".equals(question.type()) ? 2 : 1;
            if (question.correctOptionCount() != expected) {
                wrongAnswerCount++;
            }
        }
        counters.put("questions", questions.size());

        if (missingSpan > 0) {
            hardFailures.add(missingSpan + " question(s) have no source span");
        }
        if (unresolvableQuestionSpan > 0) {
            hardFailures.add(unresolvableQuestionSpan + " question source span(s) do not resolve");
        }
        if (missingTask > 0) {
            hardFailures.add(missingTask + " question(s) are not anchored to a task statement");
        }
        if (wrongAnswerCount > 0) {
            hardFailures.add(wrongAnswerCount + " question(s) have the wrong number of correct answers");
        }

        // --- schema validity rate -------------------------------------------
        long schemaValid = packs.stream().filter(PackSample::schemaValid).count();
        double schemaRate = packs.isEmpty() ? 0 : (double) schemaValid / packs.size();
        if (schemaRate < 0.99) {
            hardFailures.add("schema-valid rate %.1f%% is below the required 99%%"
                    .formatted(schemaRate * 100));
        }

        // --- domain coverage, release only ----------------------------------
        if (level == Level.RELEASE && domainsCovered.size() < 5) {
            hardFailures.add("release evaluation must cover every launch exam domain, covered "
                    + domainsCovered.size());
        }

        // --- human scores -----------------------------------------------------
        List<String> qualityFailures = new ArrayList<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        record Dimension(String name, Double value) { }
        List<Dimension> dimensions = List.of(
                new Dimension("factualCorrectness", human.factualCorrectness()),
                new Dimension("grounding", human.grounding()),
                new Dimension("examRelevance", human.examRelevance()),
                new Dimension("vietnameseClarity", human.vietnameseClarity()),
                new Dimension("explanationUsefulness", human.explanationUsefulness()),
                new Dimension("quizQuality", human.quizQuality()),
                new Dimension("shareAcceptedWithoutRewrite", human.shareAcceptedWithoutRewrite()));

        for (Dimension dimension : dimensions) {
            double target = QUALITY_TARGETS.get(dimension.name());
            if (dimension.value() == null) {
                if (level == Level.RELEASE) {
                    qualityFailures.add(dimension.name() + " has not been reviewed");
                }
                continue;
            }
            scores.put(dimension.name(), dimension.value());
            if (dimension.value() < target) {
                qualityFailures.add("%s %.2f is below the target %.2f"
                        .formatted(dimension.name(), dimension.value(), target));
            }
        }

        // --- reviewer defect gates: zero, or not reviewed ------------------
        Map<String, Integer> reviewedFindings = new LinkedHashMap<>();
        for (String gate : findings.gateNames()) {
            Integer value = findings.valueOf(gate);
            if (value == null) {
                if (level == Level.RELEASE) {
                    hardFailures.add(gate + " has not been reviewed");
                }
                continue;
            }
            reviewedFindings.put(gate, value);
            if (value > 0) {
                hardFailures.add("%s = %d, and the gate is zero".formatted(gate, value));
            }
        }

        boolean passed = hardFailures.isEmpty()
                && (level == Level.SMOKE || qualityFailures.isEmpty());

        return new Report(level, passed, hardFailures, qualityFailures, counters, scores,
                schemaRate, reviewedFindings, cost);
    }

    /** Matches on a normalised leading fragment, the same way the quiz validator does. */
    private static boolean resolves(String span, String source) {
        if (source == null || source.isBlank()) {
            return true;
        }
        String normalisedSpan = normalise(span);
        if (normalisedSpan.length() < 15) {
            return true;
        }
        String probe = normalisedSpan.substring(0, Math.min(60, normalisedSpan.length()));
        return normalise(source).contains(probe);
    }

    private static String normalise(String text) {
        return text == null ? "" : text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static List<JsonNode> readBlocks(String payload) {
        List<JsonNode> blocks = new ArrayList<>();
        try {
            MAPPER.readTree(payload).path("blocks").forEach(blocks::add);
        } catch (Exception ignored) {
            // A payload that cannot be read counts through schemaValid instead.
        }
        return blocks;
    }

    public record PackSample(String unitId, String payload, boolean schemaValid) {
    }

    public record QuestionSample(String unitId, String type, String taskStatementId,
                                 String sourceSpan, int correctOptionCount) {
    }

    public record Report(Level level,
                         boolean passed,
                         List<String> hardFailures,
                         List<String> qualityFailures,
                         Map<String, Integer> counters,
                         Map<String, Double> humanScores,
                         double schemaValidRate,
                         Map<String, Integer> reviewerFindings,
                         CostSummary cost) {

        /** Human-readable report, printed by the eval runner and pasted into the record. */
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== ").append(level).append(" EVAL — ")
              .append(passed ? "PASS" : "FAIL").append(" ===\n");
            counters.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
            sb.append(String.format("  schema-valid rate: %.1f%%%n", schemaValidRate * 100));

            sb.append("  hard gates: ");
            if (hardFailures.isEmpty()) {
                sb.append("all passed\n");
            } else {
                sb.append('\n');
                hardFailures.forEach(f -> sb.append("    FAIL ").append(f).append('\n'));
            }

            if (reviewerFindings.isEmpty()) {
                sb.append("  reviewer defect gates: not reviewed\n");
            } else {
                sb.append("  reviewer defect gates (all must be zero):\n");
                reviewerFindings.forEach((k, v) -> sb.append(String.format(
                        "    %-36s %d%n", k, v)));
            }

            sb.append(renderCost());

            if (humanScores.isEmpty()) {
                sb.append("  human review: not recorded\n");
            } else {
                sb.append("  human review:\n");
                humanScores.forEach((k, v) -> sb.append(String.format(
                        "    %-28s %.2f (target %.2f)%n", k, v, QUALITY_TARGETS.get(k))));
            }
            qualityFailures.forEach(f -> sb.append("    FAIL ").append(f).append('\n'));

            if (level == Level.RELEASE) {
                sb.append("  note: prompt freeze requires TWO consecutive passing release runs\n");
            }
            return sb.toString();
        }

        /** D6: 500 cents per plan is the target, 1000 cents the review threshold. */
        private String renderCost() {
            if (cost == null || (cost.totalCents() == 0 && cost.byOperation().isEmpty())) {
                return "  cost: not measured\n";
            }
            StringBuilder sb = new StringBuilder("  cost:\n");
            sb.append(String.format("    generated by: %s%n", cost.provenance()));
            sb.append(String.format("    pricing:      %s%n", cost.pricingIsPublished()
                    ? "published per-model prices"
                    : "TIER ESTIMATE - real prices not configured, treat as indicative"));
            sb.append(String.format("    %-32s %6s %5s %9s %6s %9s %10s %7s%n",
                    "operation", "calls", "ok", "rejected", "cache",
                    "tokensIn", "tokensOut", "cents"));
            for (OperationCost op : cost.byOperation()) {
                sb.append(String.format("    %-32s %6d %5d %9d %6d %9d %10d %7d%n",
                        op.operationId(), op.calls(), op.successes(), op.rejected(),
                        op.cacheHits(), op.tokensIn(), op.tokensOut(), op.costCents()));
            }
            sb.append(String.format("    %-32s %6s %5s %9s %6d %9d %10d %7d%n",
                    "TOTAL", "", "", "", cost.cacheHits(), cost.tokensIn(), cost.tokensOut(),
                    cost.totalCents()));
            sb.append(String.format("    retry overhead: %d cents (%.1f%% of spend)%n",
                    cost.retryCents(),
                    cost.totalCents() == 0 ? 0.0 : 100.0 * cost.retryCents() / cost.totalCents()));
            sb.append(String.format("    per material %s | per learning unit %s | per study day %s%n",
                    per(cost.totalCents(), cost.materials()),
                    per(cost.totalCents(), cost.units()),
                    per(cost.totalCents(), cost.studyDays())));
            sb.append(String.format("    against D6: %d cents this run (target <= 500, review at 1000)%n",
                    cost.totalCents()));
            return sb.toString();
        }

        private static String per(int cents, int count) {
            return count <= 0 ? "n/a" : String.format("%.1fc", (double) cents / count);
        }
    }
}
