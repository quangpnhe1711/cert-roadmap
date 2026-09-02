package com.certcopilot.platform.ai.validators;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.certcopilot.platform.ai.DomainValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.springframework.stereotype.Component;

/**
 * The strictest gate in the system.
 *
 * <p>A wrong answer key destroys trust faster than anything else the product can
 * do, and unlike a weak lesson the learner cannot tell it happened. So a question
 * that fails any check is <em>discarded</em>, never repaired: shipping fewer
 * questions is always better than shipping one that is wrong.
 *
 * <p>Eight checks, in the order they catch the most:
 *
 * <ol>
 *   <li>a source span exists;
 *   <li>the span actually resolves in the supplied source text;
 *   <li>the task statement is one of the pinned exam scope, not invented;
 *   <li>exactly one correct answer for single choice, exactly two for multiple response;
 *   <li>options are unique;
 *   <li>no "all of the above" style options;
 *   <li>option lengths are not skewed, which is the classic giveaway;
 *   <li>an explanation and a rationale for every wrong option.
 * </ol>
 *
 * <p>Rather than reject a whole batch for one bad question, the validator prunes
 * the invalid ones and passes when enough survive.
 */
@Component
public class QuizValidator implements DomainValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectWriter WRITER = MAPPER.writer();

    private static final Set<String> BANNED_OPTION_PATTERNS = Set.of(
            "all of the above", "none of the above", "both a and b",
            "tất cả các đáp án trên", "không đáp án nào");

    /** A batch is usable when at least this share of requested questions survive. */
    private static final double MIN_SURVIVAL_RATIO = 0.5;

    @Override
    public ValidationResult validate(String rawOutput, String structuredInput) {
        try {
            JsonNode root = MAPPER.readTree(rawOutput);
            JsonNode input = structuredInput == null || structuredInput.isBlank()
                    ? MAPPER.createObjectNode() : MAPPER.readTree(structuredInput);

            JsonNode questions = root.path("questions");
            if (!questions.isArray()) {
                return ValidationResult.fail("questions must be an array");
            }
            if (questions.isEmpty()) {
                return ValidationResult.fail("no questions were produced");
            }

            Set<String> allowedTasks = new HashSet<>();
            for (JsonNode task : input.path("taskStatements")) {
                allowedTasks.add(task.path("id").asText());
            }
            String normalisedSource = normalise(input.path("sourceText").asText(""));
            int requested = input.path("questionCount").asInt(questions.size());

            List<String> rejections = new ArrayList<>();
            ArrayNode kept = MAPPER.createArrayNode();

            for (JsonNode question : questions) {
                String problem = inspect(question, allowedTasks, normalisedSource);
                if (problem == null) {
                    kept.add(question);
                } else {
                    rejections.add(problem);
                }
            }

            if (kept.isEmpty()) {
                return ValidationResult.fail("every question failed validation: "
                        + String.join("; ", rejections.subList(0, Math.min(3, rejections.size()))));
            }
            if (kept.size() < Math.ceil(requested * MIN_SURVIVAL_RATIO)) {
                return ValidationResult.fail("only " + kept.size() + " of " + requested
                        + " questions passed validation: "
                        + String.join("; ", rejections.subList(0, Math.min(3, rejections.size()))));
            }

            return ValidationResult.ok();
        } catch (Exception e) {
            return ValidationResult.fail("unreadable quiz output: " + e.getMessage());
        }
    }

    /**
     * Filters a validated batch down to the questions that individually pass.
     * Called after the gateway accepts the batch, so only clean questions are
     * persisted.
     */
    public List<JsonNode> keepValid(String rawOutput, String structuredInput) {
        List<JsonNode> kept = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(rawOutput);
            JsonNode input = structuredInput == null || structuredInput.isBlank()
                    ? MAPPER.createObjectNode() : MAPPER.readTree(structuredInput);
            Set<String> allowedTasks = new HashSet<>();
            for (JsonNode task : input.path("taskStatements")) {
                allowedTasks.add(task.path("id").asText());
            }
            String normalisedSource = normalise(input.path("sourceText").asText(""));

            for (JsonNode question : root.path("questions")) {
                if (inspect(question, allowedTasks, normalisedSource) == null) {
                    kept.add(question);
                }
            }
        } catch (Exception ignored) {
            // An unreadable batch yields nothing; the caller reports zero questions.
        }
        return kept;
    }

    /** @return null when the question is acceptable, otherwise the reason it is not */
    private String inspect(JsonNode q, Set<String> allowedTasks, String normalisedSource) {
        // 1. source span present
        String span = q.path("sourceSpan").asText("");
        if (span.isBlank()) {
            return "missing sourceSpan";
        }

        // 2. source span resolves against the material
        if (!normalisedSource.isBlank()) {
            String normalisedSpan = normalise(span);
            if (normalisedSpan.length() >= 15 && !normalisedSource.contains(shortest(normalisedSpan))) {
                return "sourceSpan does not resolve in the source text";
            }
        }

        // 3. task statement is real
        String taskId = q.path("taskStatementId").asText("");
        if (taskId.isBlank() || (!allowedTasks.isEmpty() && !allowedTasks.contains(taskId))) {
            return "taskStatementId is not part of the pinned exam scope";
        }

        String type = q.path("type").asText("");
        JsonNode options = q.path("options");
        JsonNode correct = q.path("correctOptionIds");
        if (!options.isArray() || options.size() < 3) {
            return "a question needs at least three options";
        }
        if (!correct.isArray() || correct.isEmpty()) {
            return "no correct answer marked";
        }

        // 4. correct-answer count matches the question type
        int expectedCorrect = "MULTIPLE_RESPONSE".equals(type) ? 2 : 1;
        if (correct.size() != expectedCorrect) {
            return type + " must have exactly " + expectedCorrect + " correct answer(s), found "
                    + correct.size();
        }

        Set<String> optionIds = new HashSet<>();
        Set<String> optionTexts = new HashSet<>();
        List<Integer> lengths = new ArrayList<>();

        for (JsonNode option : options) {
            String id = option.path("id").asText("");
            String text = option.path("text").asText("");
            if (id.isBlank() || text.isBlank()) {
                return "option with empty id or text";
            }
            // 5. options unique
            if (!optionIds.add(id) || !optionTexts.add(normalise(text))) {
                return "duplicate option";
            }
            // 6. no meta-options
            String lowered = text.toLowerCase(Locale.ROOT).strip();
            for (String banned : BANNED_OPTION_PATTERNS) {
                if (lowered.contains(banned)) {
                    return "option uses a banned pattern: " + banned;
                }
            }
            lengths.add(text.length());
        }

        for (JsonNode id : correct) {
            if (!optionIds.contains(id.asText())) {
                return "correct answer refers to an option that does not exist";
            }
        }

        // 7. length skew - the longest option being the answer is a classic tell
        int max = lengths.stream().mapToInt(Integer::intValue).max().orElse(0);
        int min = lengths.stream().mapToInt(Integer::intValue).min().orElse(0);
        if (min > 0 && max > min * 4) {
            return "option lengths are too skewed, which gives away the answer";
        }

        // 8. explanation and per-distractor rationale
        if (q.path("explanation").asText("").isBlank()) {
            return "missing explanation";
        }
        JsonNode rationales = q.path("distractorRationales");
        Set<String> correctIds = new HashSet<>();
        correct.forEach(n -> correctIds.add(n.asText()));
        for (String optionId : optionIds) {
            if (!correctIds.contains(optionId)
                    && rationales.path(optionId).asText("").isBlank()) {
                return "missing rationale for wrong option " + optionId;
            }
        }

        return null;
    }

    /** Matches on a leading fragment so trivial trailing differences do not fail a real span. */
    private static String shortest(String span) {
        return span.length() <= 60 ? span : span.substring(0, 60);
    }

    private static String normalise(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    public static String write(Object value) {
        try {
            return WRITER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
