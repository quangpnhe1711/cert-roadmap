package com.certcopilot.platform.ai.validators;

import java.util.List;
import java.util.Map;

import com.certcopilot.shared.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four promises a generated lesson has to keep.
 *
 * <p>Traceability is what makes "companion, not replacement" true rather than a
 * slogan; must-know coverage is what stops a fluent lesson that skipped the
 * examinable part; and the overlap check is what stops a "summary" from quietly
 * becoming a copy of someone's paid course material. None of the three had a
 * test.
 */
class LearningPackValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SOURCE = """
            Amazon Bedrock là dịch vụ được quản trị hoàn toàn, cung cấp quyền truy cập
            vào các foundation model thông qua một API duy nhất. Người dùng không phải
            quản lý hạ tầng, không phải cấp phát GPU và chỉ trả tiền theo lượng token
            đã sử dụng trong quá trình suy luận của mô hình.""";

    private final LearningPackValidator validator = new LearningPackValidator();

    @Test
    @DisplayName("a traceable, covering, original lesson passes")
    void goodPackPasses() {
        assertThat(validator.validate(pack(materialBlock(1, 2, "Bedrock cho phép gọi mô hình "
                + "nền qua một API duy nhất, không cần tự dựng hạ tầng.")), input()).valid())
                .as("the fixture must pass, or every rejection below proves nothing")
                .isTrue();
    }

    // --------------------------------------------------------- traceability

    @Test
    @DisplayName("a block claiming material origin with no page range is rejected")
    void materialOriginNeedsAPageRange() {
        ObjectNode block = materialBlock(1, 2, "Nội dung lấy từ tài liệu.");
        block.remove("sourcePageStart");

        assertThat(validator.validate(pack(block), input()).valid())
                .as("an untraceable claim of material origin is the failure the "
                        + "companion positioning cannot survive")
                .isFalse();
    }

    @Test
    @DisplayName("a page range outside the unit is rejected")
    void pageRangeMustStayInsideTheUnit() {
        assertThat(validator.validate(pack(materialBlock(7, 9, "Nội dung.")), input()).valid())
                .isFalse();
    }

    @Test
    @DisplayName("supplementary background needs no page range, because it claims none")
    void aiSupplementNeedsNoPageRange() {
        ObjectNode block = block("concept", "AI_SUPPLEMENT",
                "Bổ sung kiến thức nền không có trong tài liệu.");

        assertThat(validator.validate(pack(block), input()).valid()).isTrue();
    }

    // ------------------------------------------------------------ coverage

    @Test
    @DisplayName("a lesson that never mentions a required exam point is rejected")
    void mustKnowCoverageIsEnforced() {
        String withMustKnow = Json.write(Map.of(
                "pageStart", 1, "pageEnd", 4, "sourceText", SOURCE,
                "mustKnow", List.of("Responsible AI guardrails filtering harmful content")));

        assertThat(validator.validate(
                pack(materialBlock(1, 2, "Bedrock cung cấp mô hình nền qua API.")), withMustKnow)
                .valid())
                .as("fluent prose that skipped the examinable point is not a finished lesson")
                .isFalse();
    }

    @Test
    @DisplayName("a faithful Vietnamese rewrite of an English exam point counts as covering it")
    void coverageToleratesTranslation() {
        String withMustKnow = Json.write(Map.of(
                "pageStart", 1, "pageEnd", 4, "sourceText", SOURCE,
                "mustKnow", List.of("guardrails filtering harmful content")));

        // Demanding a literal match would fail every correct Vietnamese lesson.
        assertThat(validator.validate(pack(materialBlock(1, 2,
                "Tính năng guardrails dùng để filtering nội dung harmful trước khi trả về.")),
                withMustKnow).valid()).isTrue();
    }

    // ------------------------------------------------------------- copyright

    @Test
    @DisplayName("a lesson that reproduces the source verbatim is rejected")
    void verbatimReproductionIsRejected() {
        assertThat(validator.validate(pack(materialBlock(1, 2, SOURCE)), input()).valid())
                .as("a 'summary' that is the source is a copy of paid course material")
                .isFalse();
    }

    @Test
    @DisplayName("overlap is measured on long shingles, so shared terminology is not plagiarism")
    void overlapIgnoresSharedTerminology() {
        // Keeping "foundation model" and "Amazon Bedrock" is correct behaviour, not
        // copying; a shorter n-gram would make every accurate lesson fail.
        double sameTerms = LearningPackValidator.overlapRatio(
                "Amazon Bedrock cho phép truy cập foundation model mà không cần quản lý hạ tầng.",
                SOURCE);
        double copied = LearningPackValidator.overlapRatio(SOURCE, SOURCE);

        assertThat(sameTerms).isLessThan(0.25);
        assertThat(copied).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("overlap against no source is zero rather than an error")
    void overlapIsTotal() {
        assertThat(LearningPackValidator.overlapRatio("bất kỳ", "")).isZero();
        assertThat(LearningPackValidator.overlapRatio("", SOURCE)).isZero();
        assertThat(LearningPackValidator.overlapRatio(null, SOURCE)).isZero();
    }

    // ----------------------------------------------------------------- shape

    @Test
    @DisplayName("an unknown block type or origin is rejected")
    void shapeIsEnforced() {
        assertThat(validator.validate(pack(block("freeform", "FROM_MATERIAL", "x")), input()).valid())
                .isFalse();

        ObjectNode wrongOrigin = materialBlock(1, 2, "Nội dung.");
        wrongOrigin.put("origin", "SCRAPED_FROM_WEB");
        assertThat(validator.validate(pack(wrongOrigin), input()).valid()).isFalse();
    }

    @Test
    @DisplayName("an empty or unreadable pack is rejected")
    void emptyPackIsRejected() {
        assertThat(validator.validate("{\"blocks\":[]}", input()).valid()).isFalse();
        assertThat(validator.validate("{}", input()).valid()).isFalse();
        assertThat(validator.validate("not json", input()).valid()).isFalse();
    }

    // ----------------------------------------------------------------- setup

    private static String input() {
        return Json.write(Map.of(
                "pageStart", 1, "pageEnd", 4, "sourceText", SOURCE, "mustKnow", List.of()));
    }

    private static String pack(ObjectNode... blocks) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ObjectNode block : blocks) {
            array.add(block);
        }
        return MAPPER.createObjectNode().set("blocks", array).toString();
    }

    private static ObjectNode materialBlock(int pageStart, int pageEnd, String text) {
        ObjectNode node = block("concept", "FROM_MATERIAL", text);
        node.put("sourcePageStart", pageStart);
        node.put("sourcePageEnd", pageEnd);
        return node;
    }

    private static ObjectNode block(String type, String origin, String text) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        node.put("origin", origin);
        node.put("examRelevance", "MUST_KNOW");
        node.set("payload", MAPPER.createObjectNode().put("text", text));
        return node;
    }
}
