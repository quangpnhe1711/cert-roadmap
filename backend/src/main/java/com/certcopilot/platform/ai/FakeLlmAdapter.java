package com.certcopilot.platform.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic, offline stand-in for a language model.
 *
 * <p>It is not a throwaway stub. It produces schema-valid, input-derived output
 * for every declared operation so that the whole pipeline - validators, bounded
 * retry, budget reservation, cost ledger, artifact caching - is exercised in
 * development and in CI without a network call, an API key or a bill. CI must
 * never call a real provider, and a fake that returns rubbish would make the
 * tests meaningless.
 *
 * <p>What it deliberately does not do is produce good Vietnamese teaching prose.
 * That is what the evaluation harness and a real provider are for; content
 * quality is measured, not asserted.
 */
@Component
@ConditionalOnProperty(name = "app.ai.adapter", havingValue = "fake", matchIfMissing = true)
public class FakeLlmAdapter implements LlmPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Response call(Request request) {
        long start = System.nanoTime();
        JsonNode input = readInput(request.structuredInput());

        String body;
        if (shouldFailThisAttempt(request, input)) {
            // Structurally parseable but domain-invalid, so the domain validator
            // is what rejects it. That is the interesting failure path.
            body = "{}";
        } else {
            body = switch (request.operationId()) {
                case "material.structure.extract" -> structure(input);
                case "material.topics.extract" -> topics(input);
                case "mapping.topic.to.task" -> mappings(input);
                case "learning.pack.generate" -> pack(input);
                case "assessment.quiz.generate" -> quiz(input);
                case "assessment.weakness.explain" -> weakness(input);
                default -> "{}";
            };
        }

        int tokensIn = Math.max(32, (request.prompt() == null ? 0 : request.prompt().length()) / 4);
        int tokensOut = Math.max(48, body.length() / 4);
        long latencyMs = Math.max(1, (System.nanoTime() - start) / 1_000_000);

        return new Response(body, SYNTHETIC_MODEL_PREFIX + request.tier().name().toLowerCase() + "-v1",
                tokensIn, tokensOut, latencyMs);
    }

    /**
     * Test hooks. A material whose file name contains {@code retry} fails once so
     * the "a rejected attempt is still billed" behaviour can be demonstrated end
     * to end; {@code failalways} never succeeds.
     */
    private boolean shouldFailThisAttempt(Request request, JsonNode input) {
        String marker = input.path("testMarker").asText("");
        if (marker.contains("failalways")) {
            return true;
        }
        return marker.contains("retry") && request.attemptNo() == 1;
    }

    // ------------------------------------------------------------- operations

    private String structure(JsonNode input) {
        int pageCount = input.path("pageCount").asInt(1);
        int target = Math.max(1, Math.min(14, pageCount / 18 + 1));
        int span = (int) Math.ceil((double) pageCount / target);

        ArrayNode sections = MAPPER.createArrayNode();
        ArrayNode digest = (ArrayNode) input.path("pages");
        int index = 0;
        for (int start = 1; start <= pageCount; start += span) {
            int end = Math.min(pageCount, start + span - 1);
            String title = titleAt(digest, start);
            ObjectNode section = MAPPER.createObjectNode();
            section.put("title", title != null ? title : "Phần " + (index + 1));
            section.put("pageStart", start);
            section.put("pageEnd", end);
            section.put("level", 0);
            sections.add(section);
            index++;
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.set("sections", sections);
        return root.toString();
    }

    private String topics(JsonNode input) {
        ArrayNode out = MAPPER.createArrayNode();
        ArrayNode sections = (ArrayNode) input.path("sections");
        for (int i = 0; i < sections.size(); i++) {
            JsonNode section = sections.get(i);
            int pageStart = section.path("pageStart").asInt(1);
            int pageEnd = section.path("pageEnd").asInt(pageStart);
            String title = section.path("title").asText("Phần " + (i + 1));

            ObjectNode topic = MAPPER.createObjectNode();
            topic.put("sectionIndex", i);
            topic.put("title", title);
            topic.put("pageStart", pageStart);
            topic.put("pageEnd", pageEnd);
            // Stable pseudo-difficulty so estimates are reproducible across runs.
            topic.put("difficultyTier", 2 + Math.floorMod(hash(title), 3));
            ArrayNode keywords = MAPPER.createArrayNode();
            for (String keyword : keywordsFrom(section.path("sample").asText(""))) {
                keywords.add(keyword);
            }
            topic.set("keywords", keywords);
            out.add(topic);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.set("topics", out);
        return root.toString();
    }

    private String mappings(JsonNode input) {
        ArrayNode units = (ArrayNode) input.path("units");
        ArrayNode tasks = (ArrayNode) input.path("taskStatements");
        ArrayNode out = MAPPER.createArrayNode();

        if (tasks.isEmpty()) {
            ObjectNode root = MAPPER.createObjectNode();
            root.set("mappings", out);
            return root.toString();
        }

        String[] relevances = {"CRITICAL", "HIGH", "HIGH", "MEDIUM", "MEDIUM", "LOW"};
        for (int i = 0; i < units.size(); i++) {
            JsonNode unit = units.get(i);
            // Deterministic spread across the exam scope so coverage analysis has
            // something realistic to work with: most tasks get hit, some do not.
            int primary = Math.floorMod(hash(unit.path("title").asText("") + i), tasks.size());
            addMapping(out, unit, tasks.get(primary), relevances[i % relevances.length], 0.82);

            if (i % 3 == 0 && tasks.size() > 1) {
                int secondary = (primary + 1 + Math.floorMod(hash(unit.path("id").asText("")),
                        Math.max(1, tasks.size() - 1))) % tasks.size();
                if (secondary != primary) {
                    addMapping(out, unit, tasks.get(secondary), "MEDIUM", 0.61);
                }
            }
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.set("mappings", out);
        return root.toString();
    }

    private void addMapping(ArrayNode out, JsonNode unit, JsonNode task,
                            String relevance, double confidence) {
        ObjectNode mapping = MAPPER.createObjectNode();
        mapping.put("unitId", unit.path("id").asText());
        mapping.put("taskStatementId", task.path("id").asText());
        mapping.put("relevance", relevance);
        mapping.put("confidence", confidence);
        mapping.put("rationale", "Nội dung unit trùng với phạm vi của task statement "
                + task.path("code").asText());
        out.add(mapping);
    }

    private String pack(JsonNode input) {
        int pageStart = input.path("pageStart").asInt(1);
        int pageEnd = input.path("pageEnd").asInt(pageStart);
        String title = input.path("title").asText("Nội dung");
        String sourceText = input.path("sourceText").asText("");
        boolean hasVisual = input.path("hasSignificantVisual").asBoolean(false);
        JsonNode mustKnow = input.path("mustKnow");

        ArrayNode blocks = MAPPER.createArrayNode();

        blocks.add(block("overview", "FROM_MATERIAL", "SHOULD_KNOW", pageStart, pageEnd,
                "Phần này tóm tắt " + title + " dựa trên trang " + pageStart + "–" + pageEnd
                        + " trong tài liệu của bạn."));

        // One concept block per must-know item, so the coverage validator has
        // something real to check rather than always trivially passing.
        for (JsonNode item : mustKnow) {
            blocks.add(block("concept", "FROM_MATERIAL", "MUST_KNOW", pageStart, pageEnd,
                    item.asText()));
        }

        for (String keyword : keywordsFrom(sourceText)) {
            ObjectNode keywordBlock = MAPPER.createObjectNode();
            keywordBlock.put("type", "keyword");
            keywordBlock.put("origin", "FROM_MATERIAL");
            keywordBlock.put("examRelevance", "SHOULD_KNOW");
            keywordBlock.put("sourcePageStart", pageStart);
            keywordBlock.put("sourcePageEnd", pageEnd);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("term", keyword);
            payload.put("simple", "Giải thích ngắn gọn cho thuật ngữ " + keyword + ".");
            payload.put("examNote", "Cần nhận biết " + keyword + " trong tình huống đề bài.");
            keywordBlock.set("payload", payload);
            blocks.add(keywordBlock);
        }

        blocks.add(block("exam_tip", "AI_SUPPLEMENT", "MUST_KNOW", pageStart, pageEnd,
                "Với kỳ thi này bạn cần nhận biết khái niệm, không cần đi sâu vào công thức."));

        if (hasVisual) {
            blocks.add(block("source_reference", "FROM_MATERIAL", "SHOULD_KNOW", pageStart, pageEnd,
                    "Phần này có sơ đồ quan trọng. Hãy mở slide gốc trang "
                            + pageStart + "–" + pageEnd + " để xem hình."));
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.set("blocks", blocks);
        return root.toString();
    }

    private ObjectNode block(String type, String origin, String relevance,
                             int pageStart, int pageEnd, String text) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        node.put("origin", origin);
        node.put("examRelevance", relevance);
        node.put("sourcePageStart", pageStart);
        node.put("sourcePageEnd", pageEnd);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("text", text);
        node.set("payload", payload);
        return node;
    }

    private String quiz(JsonNode input) {
        int count = input.path("questionCount").asInt(6);
        int pageStart = input.path("pageStart").asInt(1);
        int pageEnd = input.path("pageEnd").asInt(pageStart);
        String sourceText = input.path("sourceText").asText("");
        ArrayNode tasks = (ArrayNode) input.path("taskStatements");

        ArrayNode questions = MAPPER.createArrayNode();
        List<String> spans = sentencesFrom(sourceText);
        if (tasks.isEmpty() || spans.isEmpty()) {
            // No resolvable source means no question. Producing one anyway is the
            // exact failure the validator exists to prevent.
            ObjectNode root = MAPPER.createObjectNode();
            root.set("questions", questions);
            return root.toString();
        }

        for (int i = 0; i < count; i++) {
            String span = spans.get(i % spans.size());
            JsonNode task = tasks.get(i % tasks.size());
            boolean multi = i % 4 == 3;

            ObjectNode q = MAPPER.createObjectNode();
            q.put("type", multi ? "MULTIPLE_RESPONSE" : (i % 3 == 2 ? "SCENARIO_SINGLE" : "SINGLE_CHOICE"));
            q.put("taskStatementId", task.path("id").asText());
            q.put("stem", multi
                    ? "Chọn HAI phát biểu đúng về nội dung liên quan tới "
                        + task.path("title").asText() + "."
                    : "Theo tài liệu, phát biểu nào sau đây đúng về "
                        + task.path("title").asText() + "?");

            ArrayNode options = MAPPER.createArrayNode();
            String[] ids = {"A", "B", "C", "D", "E"};
            int correctIndex = Math.floorMod(hash(span + i), 5);
            int secondCorrect = (correctIndex + 2) % 5;
            ArrayNode correct = MAPPER.createArrayNode();

            String secondSpan = spans.get((i + 1) % spans.size());
            for (int o = 0; o < 5; o++) {
                ObjectNode option = MAPPER.createObjectNode();
                option.put("id", ids[o]);
                boolean isCorrect = o == correctIndex || (multi && o == secondCorrect);
                // Two correct options must differ; identical text is a duplicate
                // and the validator is right to reject it.
                String text = isCorrect
                        ? truncate(o == correctIndex ? span : secondSpan, 90)
                        : "Phát biểu không khớp với nội dung nguồn (biến thể " + (o + 1) + ")";
                option.put("text", text);
                options.add(option);
                if (isCorrect) {
                    correct.add(ids[o]);
                }
            }
            q.set("options", options);
            q.set("correctOptionIds", correct);
            q.put("explanation", "Đáp án đúng bám sát nội dung nguồn: " + truncate(span, 120));

            ObjectNode rationales = MAPPER.createObjectNode();
            for (int o = 0; o < 5; o++) {
                boolean isCorrect = o == correctIndex || (multi && o == secondCorrect);
                if (!isCorrect) {
                    rationales.put(ids[o], "Không có căn cứ trong trang "
                            + pageStart + "–" + pageEnd + ".");
                }
            }
            q.set("distractorRationales", rationales);
            q.put("sourceSpan", truncate(span, 200));
            q.put("sourcePageStart", pageStart);
            q.put("sourcePageEnd", pageEnd);
            q.put("difficulty", 2 + Math.floorMod(hash(span), 3));
            questions.add(q);
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.set("questions", questions);
        return root.toString();
    }

    private String weakness(JsonNode input) {
        String topic = input.path("topicTitle").asText("chủ đề này");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("summary", "Bạn đang nhầm ở phần " + topic
                + ". Hãy tập trung vào điểm khác biệt chính giữa các khái niệm trong phần này.");
        ArrayNode reminders = MAPPER.createArrayNode();
        reminders.add("Đọc lại định nghĩa cốt lõi của " + topic + ".");
        reminders.add("Chú ý các tình huống đề bài hay dùng để phân biệt.");
        root.set("keyReminders", reminders);
        return root.toString();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode readInput(String structuredInput) {
        if (structuredInput == null || structuredInput.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(structuredInput);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String titleAt(ArrayNode pages, int pageNo) {
        if (pages == null) {
            return null;
        }
        for (JsonNode page : pages) {
            if (page.path("pageNo").asInt() == pageNo) {
                String title = page.path("title").asText("");
                return title.isBlank() ? null : truncate(title, 80);
            }
        }
        return null;
    }

    /**
     * Shared with the term index so the glossary and the cross-reference index
     * agree on what counts as a term.
     */
    static List<String> keywordsFrom(String text) {
        return com.certcopilot.shared.TermExtractor.extract(text, 4);
    }

    /** Real sentences from the source, so a sourceSpan actually resolves. */
    static List<String> sentencesFrom(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String candidate : text.split("(?<=[.!?])\\s+|\\n+")) {
            String trimmed = candidate.strip();
            if (trimmed.length() >= 25 && out.size() < 20) {
                out.add(trimmed);
            }
        }
        if (out.isEmpty()) {
            String trimmed = text.strip();
            if (trimmed.length() >= 10) {
                out.add(truncate(trimmed, 160));
            }
        }
        return out;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\s+", " ").strip();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private static int hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return ((digest[0] & 0xff) << 8) | (digest[1] & 0xff);
        } catch (Exception e) {
            return Math.abs(value.hashCode());
        }
    }

    /**
     * Deliberately distinct from any provider identity, so an artifact this
     * adapter produced can never be served to a deployment running a real model.
     */
    @Override
    public String generationIdentity(ModelTier tier) {
        return SYNTHETIC_MODEL_PREFIX + tier.name().toLowerCase() + "-v1";
    }
}
