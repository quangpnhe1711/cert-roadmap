package com.certcopilot.platform.ai.validators;

import java.util.List;
import java.util.Map;

import com.certcopilot.platform.ai.DomainValidator.ValidationResult;
import com.certcopilot.shared.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The strictest gate in the system, tested one rejection at a time.
 *
 * <p>A wrong answer key is the one failure a learner cannot detect for
 * themselves; they simply learn the wrong thing and find out in the exam. The
 * validator is what stands between a plausible-looking generation and that
 * outcome, so "the validator rejects X" needs to be executable rather than
 * documented.
 *
 * <p>Each test removes exactly one property from an otherwise-valid question, so
 * a failure names the check that stopped working.
 */
class QuizValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TASK_ID = "11111111-1111-4111-8111-111111111111";

    private static final String SOURCE_TEXT = """
            Amazon Bedrock là dịch vụ quản trị cung cấp foundation model qua API.
            Amazon SageMaker dùng để huấn luyện và triển khai mô hình tùy chỉnh.
            Guardrails giúp lọc nội dung không mong muốn trước khi trả về người dùng.""";

    private final QuizValidator validator = new QuizValidator();

    @Test
    @DisplayName("a well-formed grounded question passes")
    void goodQuestionPasses() {
        assertThat(validate(question())).matches(ValidationResult::valid,
                "the fixture must be valid, or every rejection test below proves nothing");
    }

    // ------------------------------------------------------------ grounding

    @Test
    @DisplayName("a question with no source span is rejected")
    void missingSourceSpanIsRejected() {
        assertThat(validate(question(q -> q.put("sourceSpan", "")))).matches(r -> !r.valid());
    }

    @Test
    @DisplayName("a source span that does not appear in the material is rejected")
    void unresolvableSourceSpanIsRejected() {
        // The failure mode this exists for: a fluent, confident quotation of
        // something the deck never said.
        assertThat(validate(question(q -> q.put("sourceSpan",
                "Amazon Bedrock tự động tinh chỉnh mô hình mỗi đêm mà không cần cấu hình"))))
                .matches(r -> !r.valid());
    }

    @Test
    @DisplayName("a task statement outside the pinned exam scope is rejected")
    void inventedTaskStatementIsRejected() {
        assertThat(validate(question(q -> q.put("taskStatementId",
                "99999999-9999-4999-8999-999999999999")))).matches(r -> !r.valid());
        assertThat(validate(question(q -> q.put("taskStatementId", "")))).matches(r -> !r.valid());
    }

    // -------------------------------------------------------------- answers

    @Test
    @DisplayName("single choice with two correct answers is rejected")
    void wrongCorrectAnswerCountIsRejected() {
        assertThat(validate(question(q -> q.set("correctOptionIds",
                MAPPER.createArrayNode().add("A").add("B"))))).matches(r -> !r.valid());
    }

    @Test
    @DisplayName("multiple response with one correct answer is rejected")
    void multipleResponseNeedsTwo() {
        assertThat(validate(question(q -> q.put("type", "MULTIPLE_RESPONSE"))))
                .matches(r -> !r.valid());
    }

    @Test
    @DisplayName("a correct answer pointing at a nonexistent option is rejected")
    void danglingCorrectAnswerIsRejected() {
        assertThat(validate(question(q -> q.set("correctOptionIds",
                MAPPER.createArrayNode().add("Z"))))).matches(r -> !r.valid());
    }

    // -------------------------------------------------------------- options

    @Test
    @DisplayName("duplicate option text is rejected")
    void duplicateOptionsAreRejected() {
        assertThat(validate(question(q -> {
            ArrayNode options = (ArrayNode) q.get("options");
            ((ObjectNode) options.get(1))
                    .put("text", options.get(0).path("text").asText());
        }))).matches(r -> !r.valid());
    }

    @Test
    @DisplayName("an all-of-the-above style option is rejected")
    void metaOptionsAreRejected() {
        assertThat(validate(question(q -> ((ObjectNode)
                q.get("options").get(2)).put("text", "Tất cả các đáp án trên"))))
                .matches(r -> !r.valid());
    }

    @Test
    @DisplayName("an answer far longer than every distractor is rejected")
    void lengthSkewIsRejected() {
        // The classic tell: a learner who has studied nothing still picks the
        // longest option and scores above chance, so the quiz stops measuring.
        assertThat(validate(question(q -> ((ObjectNode)
                q.get("options").get(0)).put("text", "Bedrock ".repeat(40)))))
                .matches(r -> !r.valid());
    }

    @Test
    @DisplayName("fewer than three options is rejected")
    void tooFewOptionsIsRejected() {
        assertThat(validate(question(q -> q.set("options",
                MAPPER.createArrayNode()
                        .add(option("A", "Bedrock"))
                        .add(option("B", "SageMaker")))))).matches(r -> !r.valid());
    }

    // --------------------------------------------------------- explanations

    @Test
    @DisplayName("a question with no explanation is rejected")
    void missingExplanationIsRejected() {
        assertThat(validate(question(q -> q.put("explanation", "")))).matches(r -> !r.valid());
    }

    @Test
    @DisplayName("a wrong option with no rationale is rejected")
    void missingDistractorRationaleIsRejected() {
        assertThat(validate(question(q -> q.set("distractorRationales",
                MAPPER.createObjectNode().put("B", "Sai vì đây là dịch vụ huấn luyện."))))
        ).matches(r -> !r.valid());
    }

    // ----------------------------------------------------------- the batch

    @Test
    @DisplayName("one bad question does not condemn the batch, and is pruned from it")
    void badQuestionsArePrunedNotRepaired() {
        String batch = batchOf(questionNode(), questionNode(q -> q.put("sourceSpan", "")),
                questionNode(), questionNode());

        assertThat(validator.validate(batch, structuredInput(4)).valid())
                .as("three of four surviving is enough to use")
                .isTrue();

        List<JsonNode> kept = validator.keepValid(batch, structuredInput(4));
        assertThat(kept)
                .as("only clean questions are persisted; a bad one is discarded, never repaired")
                .hasSize(3);
    }

    @Test
    @DisplayName("a batch where most questions fail is rejected outright")
    void mostlyBadBatchIsRejected() {
        String batch = batchOf(questionNode(), questionNode(q -> q.put("sourceSpan", "")),
                questionNode(q -> q.put("explanation", "")),
                questionNode(q -> q.put("taskStatementId", "")));

        assertThat(validator.validate(batch, structuredInput(4)).valid())
                .as("one usable question out of four means the generation went wrong")
                .isFalse();
    }

    @Test
    @DisplayName("an empty or unreadable batch is rejected rather than treated as zero questions")
    void emptyBatchIsRejected() {
        assertThat(validator.validate("{\"questions\":[]}", structuredInput(4)).valid()).isFalse();
        assertThat(validator.validate("not json at all", structuredInput(4)).valid()).isFalse();
        assertThat(validator.validate("{\"questions\":{}}", structuredInput(4)).valid()).isFalse();
    }

    @Test
    @DisplayName("keepValid on unreadable output yields nothing rather than throwing")
    void keepValidIsTotal() {
        assertThat(validator.keepValid("not json", structuredInput(4))).isEmpty();
    }

    // ----------------------------------------------------------------- setup

    private ValidationResult validate(String question) {
        return validator.validate("{\"questions\":[" + question + "]}", structuredInput(1));
    }

    private String question() {
        return questionNode().toString();
    }

    private String question(java.util.function.Consumer<ObjectNode> mutate) {
        return questionNode(mutate).toString();
    }

    private static ObjectNode questionNode() {
        return questionNode(q -> { });
    }

    private static ObjectNode questionNode(
            java.util.function.Consumer<ObjectNode> mutate) {
        ObjectNode q = MAPPER.createObjectNode();
        q.put("type", "SINGLE_CHOICE");
        q.put("stem", "Dịch vụ nào cung cấp foundation model qua API quản trị?");
        q.put("sourceSpan", "Amazon Bedrock là dịch vụ quản trị cung cấp foundation model qua API");
        q.put("taskStatementId", TASK_ID);
        q.set("options", MAPPER.createArrayNode()
                .add(option("A", "Amazon Bedrock"))
                .add(option("B", "Amazon SageMaker"))
                .add(option("C", "Amazon Guardrails")));
        q.set("correctOptionIds", MAPPER.createArrayNode().add("A"));
        q.put("explanation", "Bedrock cung cấp foundation model qua API quản trị.");
        q.set("distractorRationales", MAPPER.createObjectNode()
                .put("B", "SageMaker dùng để huấn luyện mô hình tùy chỉnh.")
                .put("C", "Guardrails là tính năng lọc nội dung, không phải dịch vụ model."));
        mutate.accept(q);
        return q;
    }

    private static ObjectNode option(String id, String text) {
        return MAPPER.createObjectNode().put("id", id).put("text", text);
    }

    private static String batchOf(Object... questions) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Object q : questions) {
            array.add((JsonNode) q);
        }
        return MAPPER.createObjectNode().set("questions", array).toString();
    }

    private static String structuredInput(int questionCount) {
        return Json.write(Map.of(
                "questionCount", questionCount,
                "sourceText", SOURCE_TEXT,
                "taskStatements", List.of(Map.of("id", TASK_ID, "code", "1.1"))));
    }
}
