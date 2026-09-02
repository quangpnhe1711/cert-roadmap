package com.certcopilot.platform.ai.validators;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.certcopilot.platform.ai.DomainValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Validators for the pipeline operations whose output is structural rather than
 * prose. Each one enforces an invariant the database would otherwise have to
 * discover the hard way.
 */
@Configuration
public class StructuralValidators {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Section ranges must tile the document exactly: ordered, contiguous, no gaps,
     * no overlaps, first starts at 1, last ends at the final page.
     *
     * <p>A gap means pages a learner never sees. An overlap means pages studied
     * twice while something else is skipped. Neither is recoverable downstream,
     * which is why this is checked before anything is written.
     */
    @Bean
    public DomainValidator structureValidator() {
        return (rawOutput, structuredInput) -> {
            try {
                JsonNode root = MAPPER.readTree(rawOutput);
                JsonNode input = structuredInput == null || structuredInput.isBlank()
                        ? MAPPER.createObjectNode() : MAPPER.readTree(structuredInput);
                int pageCount = input.path("pageCount").asInt(0);

                JsonNode sections = root.path("sections");
                if (!sections.isArray() || sections.isEmpty()) {
                    return DomainValidator.ValidationResult.fail("sections must be a non-empty array");
                }

                List<int[]> ranges = new ArrayList<>();
                for (JsonNode section : sections) {
                    if (section.path("title").asText("").isBlank()) {
                        return DomainValidator.ValidationResult.fail("section without a title");
                    }
                    int start = section.path("pageStart").asInt(-1);
                    int end = section.path("pageEnd").asInt(-1);
                    if (start < 1 || end < start) {
                        return DomainValidator.ValidationResult.fail(
                                "invalid page range " + start + "-" + end);
                    }
                    if (pageCount > 0 && end > pageCount) {
                        return DomainValidator.ValidationResult.fail(
                                "section ends at page " + end + " but the document has "
                                        + pageCount + " pages");
                    }
                    ranges.add(new int[]{start, end});
                }

                ranges.sort(Comparator.comparingInt(r -> r[0]));
                if (ranges.get(0)[0] != 1) {
                    return DomainValidator.ValidationResult.fail(
                            "the first section must start at page 1, it starts at " + ranges.get(0)[0]);
                }
                for (int i = 0; i + 1 < ranges.size(); i++) {
                    int expectedNextStart = ranges.get(i)[1] + 1;
                    if (ranges.get(i + 1)[0] != expectedNextStart) {
                        return DomainValidator.ValidationResult.fail(
                                "sections are not contiguous: expected the next section to start at "
                                        + expectedNextStart + " but it starts at " + ranges.get(i + 1)[0]);
                    }
                }
                if (pageCount > 0 && ranges.get(ranges.size() - 1)[1] != pageCount) {
                    return DomainValidator.ValidationResult.fail(
                            "sections must cover every page: they end at "
                                    + ranges.get(ranges.size() - 1)[1] + " of " + pageCount);
                }

                return DomainValidator.ValidationResult.ok();
            } catch (Exception e) {
                return DomainValidator.ValidationResult.fail(
                        "unreadable structure output: " + e.getMessage());
            }
        };
    }

    /** Topics must stay inside the section they belong to, with a sane difficulty tier. */
    @Bean
    public DomainValidator topicsValidator() {
        return (rawOutput, structuredInput) -> {
            try {
                JsonNode root = MAPPER.readTree(rawOutput);
                JsonNode input = structuredInput == null || structuredInput.isBlank()
                        ? MAPPER.createObjectNode() : MAPPER.readTree(structuredInput);
                JsonNode sections = input.path("sections");

                JsonNode topics = root.path("topics");
                if (!topics.isArray() || topics.isEmpty()) {
                    return DomainValidator.ValidationResult.fail("topics must be a non-empty array");
                }

                for (JsonNode topic : topics) {
                    if (topic.path("title").asText("").isBlank()) {
                        return DomainValidator.ValidationResult.fail("topic without a title");
                    }
                    int tier = topic.path("difficultyTier").asInt(0);
                    if (tier < 1 || tier > 5) {
                        return DomainValidator.ValidationResult.fail(
                                "difficultyTier must be 1..5, got " + tier);
                    }
                    int index = topic.path("sectionIndex").asInt(-1);
                    if (sections.isArray() && index >= 0 && index < sections.size()) {
                        JsonNode section = sections.get(index);
                        int start = topic.path("pageStart").asInt(-1);
                        int end = topic.path("pageEnd").asInt(-1);
                        if (start < section.path("pageStart").asInt()
                                || end > section.path("pageEnd").asInt()) {
                            return DomainValidator.ValidationResult.fail(
                                    "topic pages " + start + "-" + end
                                            + " fall outside their section");
                        }
                    }
                }
                return DomainValidator.ValidationResult.ok();
            } catch (Exception e) {
                return DomainValidator.ValidationResult.fail(
                        "unreadable topics output: " + e.getMessage());
            }
        };
    }

    /**
     * Mappings may only reference task statements from the pinned exam version.
     * An invented id would silently attach a lesson to a requirement that does
     * not exist, and coverage would then be wrong in a way nobody can see.
     */
    @Bean
    public DomainValidator mappingValidator() {
        return (rawOutput, structuredInput) -> {
            try {
                JsonNode root = MAPPER.readTree(rawOutput);
                JsonNode input = structuredInput == null || structuredInput.isBlank()
                        ? MAPPER.createObjectNode() : MAPPER.readTree(structuredInput);

                Set<String> allowedTasks = new HashSet<>();
                for (JsonNode task : input.path("taskStatements")) {
                    allowedTasks.add(task.path("id").asText());
                }
                Set<String> allowedUnits = new HashSet<>();
                for (JsonNode unit : input.path("units")) {
                    allowedUnits.add(unit.path("id").asText());
                }

                JsonNode mappings = root.path("mappings");
                if (!mappings.isArray()) {
                    return DomainValidator.ValidationResult.fail("mappings must be an array");
                }
                // An empty result is legitimate: material genuinely unrelated to
                // the exam should map to nothing rather than be forced.
                Set<String> relevances = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "OPTIONAL");

                for (JsonNode mapping : mappings) {
                    String taskId = mapping.path("taskStatementId").asText("");
                    if (!allowedTasks.isEmpty() && !allowedTasks.contains(taskId)) {
                        return DomainValidator.ValidationResult.fail(
                                "mapping references unknown taskStatementId " + taskId);
                    }
                    String unitId = mapping.path("unitId").asText("");
                    if (!allowedUnits.isEmpty() && !allowedUnits.contains(unitId)) {
                        return DomainValidator.ValidationResult.fail(
                                "mapping references unknown unitId " + unitId);
                    }
                    String relevance = mapping.path("relevance").asText("");
                    if (!relevances.contains(relevance)) {
                        return DomainValidator.ValidationResult.fail(
                                "invalid relevance " + relevance);
                    }
                    double confidence = mapping.path("confidence").asDouble(-1);
                    if (confidence < 0 || confidence > 1) {
                        return DomainValidator.ValidationResult.fail(
                                "confidence must be between 0 and 1, got " + confidence);
                    }
                }
                return DomainValidator.ValidationResult.ok();
            } catch (Exception e) {
                return DomainValidator.ValidationResult.fail(
                        "unreadable mapping output: " + e.getMessage());
            }
        };
    }

    /**
     * The weakness explainer may explain, never judge. If it tries to emit a score
     * or a mastery verdict the output is rejected, because that decision belongs
     * to deterministic code.
     */
    @Bean
    public DomainValidator weaknessValidator() {
        return (rawOutput, structuredInput) -> {
            try {
                JsonNode root = MAPPER.readTree(rawOutput);
                if (root.path("summary").asText("").isBlank()) {
                    return DomainValidator.ValidationResult.fail("summary is empty");
                }
                for (String forbidden : List.of("score", "mastery", "masteryStatus", "grade")) {
                    if (root.has(forbidden)) {
                        return DomainValidator.ValidationResult.fail(
                                "the explainer must not emit '" + forbidden
                                        + "'; scoring is deterministic");
                    }
                }
                return DomainValidator.ValidationResult.ok();
            } catch (Exception e) {
                return DomainValidator.ValidationResult.fail(
                        "unreadable weakness output: " + e.getMessage());
            }
        };
    }
}
