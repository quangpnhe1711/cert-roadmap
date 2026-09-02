package com.certcopilot.domain.material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.platform.ai.AiGateway;
import com.certcopilot.platform.ai.AiOperations;
import com.certcopilot.platform.ai.AiResult;
import com.certcopilot.platform.ai.ArtifactKey;
import com.certcopilot.platform.ai.AiOperationRegistry;
import com.certcopilot.platform.ai.PromptRegistry;
import com.certcopilot.platform.jobs.JobHandler;
import com.certcopilot.platform.jobs.JobQueue;
import com.certcopilot.platform.jobs.JobRecord;
import com.certcopilot.platform.parsing.DocumentParserPort;
import com.certcopilot.platform.parsing.ParsedDocument;
import com.certcopilot.platform.parsing.ParsedPage;
import com.certcopilot.shared.Json;
import com.certcopilot.shared.PageClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The document pipeline: extract, segment, structure, topics, units, effort.
 *
 * <p>Five of the six stages are deterministic. The model is asked for exactly two
 * things - section boundaries when the file carries no authored outline, and
 * topic titles with a difficulty tier - and both answers are validated before
 * they are allowed to exist.
 *
 * <p>Every stage is idempotent, so a worker that dies mid-run recovers by simply
 * running again.
 */
@Component
public class MaterialProcessingHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(MaterialProcessingHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Bumped when the structure algorithm changes; part of the material revision. */
    private static final String STRUCTURE_VERSION = "structure-v1";

    private final JdbcTemplate jdbc;
    private final MaterialService materials;
    private final List<DocumentParserPort> parsers;
    private final AiGateway gateway;
    private final AiOperationRegistry operations;
    private final PromptRegistry prompts;
    private final ExamDumpDetector dumpDetector;
    private final JobQueue jobs;

    public MaterialProcessingHandler(JdbcTemplate jdbc,
                                     MaterialService materials,
                                     List<DocumentParserPort> parsers,
                                     AiGateway gateway,
                                     AiOperationRegistry operations,
                                     PromptRegistry prompts,
                                     ExamDumpDetector dumpDetector,
                                     JobQueue jobs) {
        this.jdbc = jdbc;
        this.materials = materials;
        this.parsers = parsers;
        this.gateway = gateway;
        this.operations = operations;
        this.prompts = prompts;
        this.dumpDetector = dumpDetector;
        this.jobs = jobs;
    }

    @Override
    public String jobType() {
        return MaterialService.JOB_TYPE;
    }

    @Override
    public void handle(JobRecord job) throws Exception {
        UUID revisionId = UUID.fromString(
                MAPPER.readTree(job.payload()).path("materialRevisionId").asText());
        process(revisionId);
    }

    @Transactional
    public void process(UUID revisionId) {
        MaterialService.RevisionContext ctx = materials.revisionContext(revisionId);
        setStatus(revisionId, "EXTRACTING", null);

        // ---- S1 EXTRACT (deterministic) ------------------------------------
        DocumentParserPort parser = parsers.stream()
                .filter(p -> p.supports(ctx.mime(), ctx.fileName()))
                .findFirst()
                .orElseThrow(() -> new MaterialService.MaterialException(
                        "NO_PARSER", "no parser for " + ctx.mime()));

        ParsedDocument document;
        try {
            document = parser.parse(materials.openForRevision(revisionId), ctx.fileName());
        } catch (RuntimeException e) {
            setStatus(revisionId, "FAILED", "Không đọc được tệp: " + e.getMessage());
            throw e;
        }

        if (document.pageCount() == 0) {
            setStatus(revisionId, "FAILED", "Tệp không có nội dung đọc được.");
            return;
        }

        // Structural dump check, now that the text is available.
        String fullText = document.pages().stream()
                .map(ParsedPage::text).reduce("", (a, b) -> a + "\n" + b);
        ExamDumpDetector.Verdict dump = dumpDetector.inspectExtractedText(fullText);
        if (dump.rejected()) {
            setStatus(revisionId, "FAILED", dump.reason());
            return;
        }

        persistPages(revisionId, document);
        jdbc.update("UPDATE material SET page_count = ? WHERE id = ?",
                document.pageCount(), ctx.materialId());
        jdbc.update("UPDATE material_revision SET extraction_version = ?, quality_flags = ?::jsonb "
                        + " WHERE id = ?",
                parser.extractionVersion(), Json.write(document.qualityFlags()), revisionId);
        setStatus(revisionId, "EXTRACTED", null);

        // ---- S2 SEGMENT (deterministic) + S3 STRUCTURE (AI when needed) ----
        setStatus(revisionId, "STRUCTURING", null);
        List<StructureBuilder.Section> sections = buildStructure(revisionId, ctx, document);

        // ---- S4 TOPICS (AI) -------------------------------------------------
        List<UUID> sectionIds = persistSections(revisionId, sections);
        List<TopicRow> topics = buildTopics(revisionId, ctx, sections, sectionIds, document);
        persistTopics(topics);

        // ---- S7 UNITS + S8 EFFORT (deterministic) --------------------------
        buildLearningUnits(revisionId, sections, sectionIds, topics, document);
        buildTermIndex(revisionId, document);

        jdbc.update("UPDATE material_revision SET structure_version = ?, structure_hash = ?, "
                        + " status = 'READY' WHERE id = ?",
                STRUCTURE_VERSION, structureHash(sections), revisionId);

        // Analysis continues in the mapping stage, which needs the plan's
        // certification. Enqueued in this transaction so it cannot be lost.
        List<Map<String, Object>> plans = jdbc.queryForList(
                "SELECT id FROM study_plan WHERE material_revision_id = ? AND status = 'ANALYZING'",
                revisionId);
        for (Map<String, Object> plan : plans) {
            UUID planId = (UUID) plan.get("id");
            jobs.enqueue("PLAN_ANALYZE", "analyze:" + planId + ":" + revisionId,
                    Json.write(Map.of("planId", planId.toString())), 3);
        }

        log.info("material revision {} ready: {} pages, {} sections, {} topics",
                revisionId, document.pageCount(), sections.size(), topics.size());
    }

    // ------------------------------------------------------------- structure

    private List<StructureBuilder.Section> buildStructure(UUID revisionId,
                                                          MaterialService.RevisionContext ctx,
                                                          ParsedDocument document) {
        // Authored outline wins: the course author's own structure beats anything
        // a model reconstructs from page text.
        List<StructureBuilder.Section> fromOutline = StructureBuilder.fromOutline(document);
        if (StructureBuilder.covers(fromOutline, document.pageCount()) && fromOutline.size() >= 2) {
            log.debug("revision {} used authored outline: {} sections", revisionId, fromOutline.size());
            return fromOutline;
        }

        var operation = operations.require(AiOperations.STRUCTURE_EXTRACT);
        String structuredInput = Json.write(Map.of(
                "pageCount", document.pageCount(),
                "pages", pageDigest(document),
                "testMarker", ctx.fileName() == null ? "" : ctx.fileName().toLowerCase(Locale.ROOT)));

        String prompt = prompts.render(
                prompts.load(operation.id(), operation.promptVersion()),
                Map.of("pageCount", String.valueOf(document.pageCount()),
                        "minSections", String.valueOf(Math.max(2, document.pageCount() / 40)),
                        "maxSections", String.valueOf(Math.max(3, document.pageCount() / 12)),
                        "pageDigest", renderDigest(document)));

        ArtifactKey key = ArtifactKey.of("MATERIAL_STRUCTURE")
                .semantic("materialRevisionId", revisionId.toString())
                .semantic("extractionVersion", STRUCTURE_VERSION)
                .semantic("pageCount", String.valueOf(document.pageCount()))
                .operation(operation)
                .build();

        AiResult result = gateway.execute(operation.id(), planIdFor(revisionId), key,
                prompt, structuredInput);

        if (result instanceof AiResult.Ok ok) {
            List<StructureBuilder.Section> parsed = parseSections(ok.payload());
            List<StructureBuilder.Section> normalised =
                    StructureBuilder.normalise(parsed, document.pageCount());
            if (StructureBuilder.covers(normalised, document.pageCount())) {
                return normalised;
            }
            log.warn("revision {}: model structure did not tile the document after normalisation",
                    revisionId);
        }

        // Honest degradation: fixed-size sections, flagged low precision so the
        // learner is told and can correct the boundaries.
        log.warn("revision {} fell back to flat structure", revisionId);
        markLowPrecision(revisionId);
        return StructureBuilder.flatFallback(document, 15);
    }

    private List<StructureBuilder.Section> parseSections(String payload) {
        List<StructureBuilder.Section> sections = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(payload);
            for (JsonNode node : root.path("sections")) {
                sections.add(new StructureBuilder.Section(
                        node.path("title").asText("Phần"),
                        node.path("pageStart").asInt(1),
                        node.path("pageEnd").asInt(1),
                        node.path("level").asInt(0)));
            }
        } catch (Exception e) {
            log.warn("cannot parse structure payload: {}", e.toString());
        }
        return sections;
    }

    // ---------------------------------------------------------------- topics

    private List<TopicRow> buildTopics(UUID revisionId,
                                       MaterialService.RevisionContext ctx,
                                       List<StructureBuilder.Section> sections,
                                       List<UUID> sectionIds,
                                       ParsedDocument document) {
        var operation = operations.require(AiOperations.TOPICS_EXTRACT);

        List<Map<String, Object>> sectionInput = new ArrayList<>();
        for (StructureBuilder.Section section : sections) {
            sectionInput.add(Map.of(
                    "title", section.title(),
                    "pageStart", section.pageStart(),
                    "pageEnd", section.pageEnd(),
                    "sample", sampleText(document, section.pageStart(), section.pageEnd(), 600)));
        }

        String structuredInput = Json.write(Map.of(
                "sections", sectionInput,
                "testMarker", ctx.fileName() == null ? "" : ctx.fileName().toLowerCase(Locale.ROOT)));
        String prompt = prompts.render(
                prompts.load(operation.id(), operation.promptVersion()),
                Map.of("sections", Json.write(sectionInput)));

        ArtifactKey key = ArtifactKey.of("MATERIAL_TOPICS")
                .semantic("materialRevisionId", revisionId.toString())
                .semantic("structureHash", structureHash(sections))
                .operation(operation)
                .build();

        AiResult result = gateway.execute(operation.id(), planIdFor(revisionId), key,
                prompt, structuredInput);

        List<TopicRow> topics = new ArrayList<>();
        if (result instanceof AiResult.Ok ok) {
            try {
                JsonNode root = MAPPER.readTree(ok.payload());
                for (JsonNode node : root.path("topics")) {
                    int index = node.path("sectionIndex").asInt(-1);
                    if (index < 0 || index >= sectionIds.size()) {
                        continue;
                    }
                    List<String> keywords = new ArrayList<>();
                    node.path("keywords").forEach(k -> keywords.add(k.asText()));
                    topics.add(new TopicRow(UUID.randomUUID(), sectionIds.get(index),
                            node.path("title").asText("Chủ đề"),
                            node.path("pageStart").asInt(sections.get(index).pageStart()),
                            node.path("pageEnd").asInt(sections.get(index).pageEnd()),
                            node.path("difficultyTier").asInt(3),
                            keywords, index));
                }
            } catch (Exception e) {
                log.warn("cannot parse topics payload: {}", e.toString());
            }
        }

        // Fallback: one topic per section. The plan still works, it is just less
        // granular, which is better than no plan.
        if (topics.isEmpty()) {
            for (int i = 0; i < sections.size(); i++) {
                StructureBuilder.Section section = sections.get(i);
                topics.add(new TopicRow(UUID.randomUUID(), sectionIds.get(i), section.title(),
                        section.pageStart(), section.pageEnd(), 3, List.of(), i));
            }
        }
        return topics;
    }

    // ----------------------------------------------------------------- units

    private void buildLearningUnits(UUID revisionId,
                                    List<StructureBuilder.Section> sections,
                                    List<UUID> sectionIds,
                                    List<TopicRow> topics,
                                    ParsedDocument document) {
        jdbc.update("DELETE FROM learning_unit WHERE material_revision_id = ?", revisionId);

        Map<Integer, List<TopicRow>> topicsBySection = new HashMap<>();
        for (TopicRow topic : topics) {
            topicsBySection.computeIfAbsent(topic.sectionIndex(), k -> new ArrayList<>()).add(topic);
        }

        int sequence = 0;
        for (int i = 0; i < sections.size(); i++) {
            StructureBuilder.Section section = sections.get(i);
            List<ParsedPage> sectionPages = pagesIn(document, section.pageStart(), section.pageEnd());
            PageClass dominant = dominantClass(sectionPages);
            int span = EffortEstimator.suggestedUnitPageSpan(dominant);

            List<TopicRow> sectionTopics = topicsBySection.getOrDefault(i, List.of());
            int tier = sectionTopics.isEmpty() ? 3
                    : (int) Math.round(sectionTopics.stream()
                            .mapToInt(TopicRow::difficultyTier).average().orElse(3));

            for (StructureBuilder.UnitRange range : StructureBuilder.splitIntoUnits(
                    section, sectionPages, span)) {
                List<ParsedPage> unitPages =
                        pagesIn(document, range.pageStart(), range.pageEnd());
                int baseEffort = EffortEstimator.baseEffortMinutes(unitPages, tier);

                List<String> topicIds = sectionTopics.stream()
                        .filter(t -> t.pageStart() <= range.pageEnd() && t.pageEnd() >= range.pageStart())
                        .map(t -> t.id().toString())
                        .toList();

                jdbc.update(
                        "INSERT INTO learning_unit (id, origin, material_revision_id, course_section_id, "
                                + " sequence, title, page_start, page_end, base_effort_minutes, "
                                + " content_hash, course_topic_ids) "
                                + "VALUES (?, 'MATERIAL', ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                        UUID.randomUUID(), revisionId, sectionIds.get(i), sequence++,
                        range.title(), range.pageStart(), range.pageEnd(), baseEffort,
                        contentHash(unitPages), Json.write(topicIds));
            }
        }
    }

    private void buildTermIndex(UUID revisionId, ParsedDocument document) {
        jdbc.update("DELETE FROM material_term_index WHERE material_revision_id = ?", revisionId);
        Map<String, Map<Integer, Integer>> index = new LinkedHashMap<>();

        for (ParsedPage page : document.pages()) {
            for (String term : com.certcopilot.shared.TermExtractor.extract(page.text(), 0)) {
                index.computeIfAbsent(term, k -> new HashMap<>())
                        .merge(page.pageNo(), 1, Integer::sum);
            }
        }

        List<Object[]> batch = new ArrayList<>();
        index.forEach((term, pages) -> pages.forEach((pageNo, count) ->
                batch.add(new Object[]{revisionId, term, pageNo, count})));
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(
                    "INSERT INTO material_term_index (material_revision_id, term, page_no, occurrences) "
                            + "VALUES (?,?,?,?) ON CONFLICT DO NOTHING", batch);
        }
    }

    // -------------------------------------------------------------- persist

    private void persistPages(UUID revisionId, ParsedDocument document) {
        jdbc.update("DELETE FROM material_page WHERE material_revision_id = ?", revisionId);
        List<Object[]> batch = new ArrayList<>();
        for (ParsedPage page : document.pages()) {
            batch.add(new Object[]{
                    UUID.randomUUID(), revisionId, page.pageNo(), page.titleGuess(),
                    page.text() == null ? "" : page.text(), page.notesText(),
                    page.wordCount(), page.imageAreaRatio(), page.pageClass().name(),
                    page.hasSignificantVisual(), page.extractionQuality()});
        }
        jdbc.batchUpdate(
                "INSERT INTO material_page (id, material_revision_id, page_no, title_guess, text, "
                        + " notes_text, word_count, image_area_ratio, page_class, "
                        + " has_significant_visual, extraction_quality) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)", batch);
    }

    private List<UUID> persistSections(UUID revisionId, List<StructureBuilder.Section> sections) {
        jdbc.update("DELETE FROM course_section WHERE material_revision_id = ?", revisionId);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            StructureBuilder.Section section = sections.get(i);
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO course_section (id, material_revision_id, title, order_index, "
                            + " depth, page_start, page_end) VALUES (?,?,?,?,?,?,?)",
                    id, revisionId, section.title(), i, section.level(),
                    section.pageStart(), section.pageEnd());
            ids.add(id);
        }
        return ids;
    }

    private void persistTopics(List<TopicRow> topics) {
        for (TopicRow topic : topics) {
            jdbc.update(
                    "INSERT INTO course_topic (id, course_section_id, title, page_start, page_end, "
                            + " difficulty_tier, keywords, order_index) VALUES (?,?,?,?,?,?,?::jsonb,?)",
                    topic.id(), topic.sectionId(), topic.title(), topic.pageStart(), topic.pageEnd(),
                    Math.max(1, Math.min(5, topic.difficultyTier())),
                    Json.write(topic.keywords()), topic.sectionIndex());
        }
    }

    // -------------------------------------------------------------- helpers

    private void setStatus(UUID revisionId, String status, String failureReason) {
        jdbc.update("UPDATE material_revision SET status = ?, failure_reason = ? WHERE id = ?",
                status, failureReason, revisionId);
    }

    private void markLowPrecision(UUID revisionId) {
        jdbc.update("UPDATE material_revision "
                + "   SET quality_flags = quality_flags || '{\"lowPrecisionStructure\":true}'::jsonb "
                + " WHERE id = ?", revisionId);
    }

    /** Budget scope. Material analysis is charged to the plan that requested it. */
    private UUID planIdFor(UUID revisionId) {
        List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM study_plan WHERE material_revision_id = ? LIMIT 1",
                UUID.class, revisionId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static List<Map<String, Object>> pageDigest(ParsedDocument document) {
        List<Map<String, Object>> digest = new ArrayList<>();
        for (ParsedPage page : document.pages()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("pageNo", page.pageNo());
            entry.put("title", page.titleGuess() == null ? "" : page.titleGuess());
            entry.put("words", page.wordCount());
            entry.put("class", page.pageClass().name());
            entry.put("sample", truncate(page.text(), 180));
            digest.add(entry);
        }
        return digest;
    }

    /**
     * The compact representation sent to the model. A 320-page deck is roughly
     * 90k tokens in full but about 25k as a digest, which is the difference
     * between a few dollars and a few cents for structure extraction.
     */
    private static String renderDigest(ParsedDocument document) {
        StringBuilder sb = new StringBuilder();
        for (ParsedPage page : document.pages()) {
            sb.append(page.pageNo()).append(" | ")
              .append(page.titleGuess() == null ? "" : page.titleGuess()).append(" | ")
              .append(page.wordCount()).append("w | ")
              .append(page.pageClass().name()).append(" | ")
              .append(truncate(page.text(), 160))
              .append('\n');
        }
        return sb.toString();
    }

    static String sampleText(ParsedDocument document, int pageStart, int pageEnd, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (ParsedPage page : document.pages()) {
            if (page.pageNo() >= pageStart && page.pageNo() <= pageEnd) {
                sb.append(page.text()).append('\n');
                if (sb.length() > maxChars) {
                    break;
                }
            }
        }
        return truncate(sb.toString(), maxChars);
    }

    private static List<ParsedPage> pagesIn(ParsedDocument document, int start, int end) {
        return document.pages().stream()
                .filter(p -> p.pageNo() >= start && p.pageNo() <= end)
                .toList();
    }

    private static PageClass dominantClass(List<ParsedPage> pages) {
        if (pages.isEmpty()) {
            return PageClass.TEXT_DOMINANT;
        }
        long imageHeavy = pages.stream().filter(p -> p.pageClass() == PageClass.IMAGE_DOMINANT).count();
        long mixed = pages.stream().filter(p -> p.pageClass() == PageClass.MIXED).count();
        if (imageHeavy * 2 > pages.size()) {
            return PageClass.IMAGE_DOMINANT;
        }
        if ((imageHeavy + mixed) * 2 > pages.size()) {
            return PageClass.MIXED;
        }
        return PageClass.TEXT_DOMINANT;
    }

    private static String structureHash(List<StructureBuilder.Section> sections) {
        StringBuilder sb = new StringBuilder(STRUCTURE_VERSION);
        for (StructureBuilder.Section section : sections) {
            sb.append('|').append(section.pageStart()).append('-').append(section.pageEnd())
              .append(':').append(section.title());
        }
        return MaterialService.sha256(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String contentHash(List<ParsedPage> pages) {
        StringBuilder sb = new StringBuilder();
        for (ParsedPage page : pages) {
            sb.append(page.pageNo()).append(':').append(page.wordCount())
              .append(':').append(truncate(page.text(), 120)).append('|');
        }
        return MaterialService.sha256(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\s+", " ").strip();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private record TopicRow(UUID id, UUID sectionId, String title, int pageStart, int pageEnd,
                            int difficultyTier, List<String> keywords, int sectionIndex) {
    }
}
