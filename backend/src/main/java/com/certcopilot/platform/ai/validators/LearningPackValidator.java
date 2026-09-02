package com.certcopilot.platform.ai.validators;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.certcopilot.platform.ai.DomainValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Gate for generated lessons. Four checks, each guarding a promise made to the
 * learner or to the course author.
 *
 * <ol>
 *   <li><b>Traceability</b> - a block claiming to come from the material must
 *       carry a page range inside the unit. This is what makes the companion
 *       positioning real rather than a slogan.
 *   <li><b>Must-know coverage</b> - if the exam scope says a concept is
 *       essential, a lesson that never mentions it is not finished.
 *   <li><b>Verbatim overlap</b> - a transformation, not a reproduction. Long
 *       n-gram matches against the source are how a "summary" quietly becomes a
 *       copy of paid course material.
 *   <li><b>Shape</b> - known block types, non-empty payloads.
 * </ol>
 */
@Component
public class LearningPackValidator implements DomainValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "overview", "concept", "exam_tip", "keyword", "comparison", "example",
            "warning", "relationship", "source_reference", "check_understanding");

    /** Length of the shingle used for overlap detection. */
    private static final int NGRAM = 12;

    /** Above this share of matching shingles the output is reproduction, not summary. */
    private static final double MAX_OVERLAP_RATIO = 0.25;

    @Override
    public ValidationResult validate(String rawOutput, String structuredInput) {
        try {
            JsonNode root = MAPPER.readTree(rawOutput);
            JsonNode input = structuredInput == null || structuredInput.isBlank()
                    ? MAPPER.createObjectNode() : MAPPER.readTree(structuredInput);

            JsonNode blocks = root.path("blocks");
            if (!blocks.isArray() || blocks.isEmpty()) {
                return ValidationResult.fail("blocks must be a non-empty array");
            }

            int pageStart = input.path("pageStart").asInt(1);
            int pageEnd = input.path("pageEnd").asInt(Integer.MAX_VALUE);

            StringBuilder allText = new StringBuilder();

            for (JsonNode block : blocks) {
                String type = block.path("type").asText("");
                if (!ALLOWED_TYPES.contains(type)) {
                    return ValidationResult.fail("unknown block type: " + type);
                }
                String origin = block.path("origin").asText("FROM_MATERIAL");
                if (!origin.equals("FROM_MATERIAL") && !origin.equals("AI_SUPPLEMENT")) {
                    return ValidationResult.fail("unknown block origin: " + origin);
                }
                if (block.path("payload").isMissingNode() || block.path("payload").isNull()) {
                    return ValidationResult.fail("block of type " + type + " has no payload");
                }

                if (origin.equals("FROM_MATERIAL")) {
                    if (!block.hasNonNull("sourcePageStart")) {
                        return ValidationResult.fail(
                                "block of type " + type + " claims material origin but has no page range");
                    }
                    int blockStart = block.path("sourcePageStart").asInt();
                    int blockEnd = block.path("sourcePageEnd").asInt(blockStart);
                    if (blockStart < pageStart || blockEnd > pageEnd) {
                        return ValidationResult.fail(
                                "block page range " + blockStart + "-" + blockEnd
                                        + " falls outside the unit range " + pageStart + "-" + pageEnd);
                    }
                }

                collectText(block.path("payload"), allText);
            }

            String generated = allText.toString();

            List<String> missing = missingMustKnow(input, generated);
            if (!missing.isEmpty()) {
                return ValidationResult.fail(
                        "lesson does not cover required exam points: " + String.join("; ", missing));
            }

            double overlap = overlapRatio(generated, input.path("sourceText").asText(""));
            if (overlap > MAX_OVERLAP_RATIO) {
                return ValidationResult.fail(
                        "output reproduces too much of the source verbatim (overlap "
                                + Math.round(overlap * 100) + "%); rewrite in your own words");
            }

            return ValidationResult.ok();
        } catch (Exception e) {
            return ValidationResult.fail("unreadable pack output: " + e.getMessage());
        }
    }

    private static void collectText(JsonNode payload, StringBuilder out) {
        if (payload.isTextual()) {
            out.append(payload.asText()).append('\n');
        } else if (payload.isArray()) {
            payload.forEach(child -> collectText(child, out));
        } else if (payload.isObject()) {
            payload.fields().forEachRemaining(entry -> collectText(entry.getValue(), out));
        }
    }

    /**
     * Every must-know point must be recognisable in the lesson. Matching is on
     * significant words rather than exact phrasing, because the lesson is written
     * in Vietnamese while the exam scope is in English - demanding a literal
     * match would fail every correct lesson.
     */
    private static List<String> missingMustKnow(JsonNode input, String generated) {
        List<String> missing = new ArrayList<>();
        JsonNode mustKnow = input.path("mustKnow");
        if (!mustKnow.isArray() || mustKnow.isEmpty()) {
            return missing;
        }
        String haystack = normalise(generated);

        for (JsonNode item : mustKnow) {
            String text = item.asText("");
            if (text.isBlank()) {
                continue;
            }
            List<String> significant = significantWords(text);
            if (significant.isEmpty()) {
                continue;
            }
            long hits = significant.stream().filter(haystack::contains).count();
            // Half the distinctive terms is a deliberate threshold: strict enough
            // to catch a lesson that skipped the topic, loose enough to accept a
            // faithful Vietnamese rewrite.
            if (hits * 2 < significant.size()) {
                missing.add(text);
            }
        }
        return missing;
    }

    private static List<String> significantWords(String text) {
        List<String> words = new ArrayList<>();
        for (String raw : normalise(text).split("\\s+")) {
            if (raw.length() >= 5 && !STOPWORDS.contains(raw)) {
                words.add(raw);
            }
        }
        return words;
    }

    /** Share of source shingles that reappear verbatim in the generated text. */
    static double overlapRatio(String generated, String source) {
        if (source == null || source.isBlank() || generated == null || generated.isBlank()) {
            return 0;
        }
        Set<String> sourceShingles = shingles(source);
        if (sourceShingles.isEmpty()) {
            return 0;
        }
        Set<String> generatedShingles = shingles(generated);
        if (generatedShingles.isEmpty()) {
            return 0;
        }
        long matches = generatedShingles.stream().filter(sourceShingles::contains).count();
        return (double) matches / generatedShingles.size();
    }

    private static Set<String> shingles(String text) {
        String[] words = normalise(text).split("\\s+");
        Set<String> out = new HashSet<>();
        for (int i = 0; i + NGRAM <= words.length; i++) {
            out.add(String.join(" ", java.util.Arrays.copyOfRange(words, i, i + NGRAM)));
        }
        return out;
    }

    private static String normalise(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static final Set<String> STOPWORDS = Set.of(
            "about", "which", "these", "those", "their", "there", "where", "while",
            "between", "including", "describe", "explain", "identify", "recognise",
            "recognize", "define", "compare");
}
