package com.certcopilot.domain.learning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.certcopilot.domain.catalog.CatalogService;
import com.certcopilot.platform.ai.AiGateway;
import com.certcopilot.platform.ai.AiOperationRegistry;
import com.certcopilot.platform.ai.AiOperations;
import com.certcopilot.platform.ai.AiResult;
import com.certcopilot.platform.ai.ArtifactKey;
import com.certcopilot.platform.ai.ArtifactStore;
import com.certcopilot.platform.ai.PromptRegistry;
import com.certcopilot.shared.DepthFlag;
import com.certcopilot.shared.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates and serves the daily Vietnamese lesson.
 *
 * <p>The pack is a <em>companion</em> to the learner's slides, not a replacement
 * (decision D1). Every block that claims to come from the material carries a page
 * range, so the reader can always jump to the original - which is both what makes
 * the lesson trustworthy and what keeps the output a transformation rather than a
 * reproduction of paid course content.
 *
 * <p>Output is typed blocks, never an HTML blob, so the same contract renders on
 * the web today and on mobile later.
 */
@Service
public class LearningPackService {

    private static final Logger log = LoggerFactory.getLogger(LearningPackService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final CatalogService catalog;
    private final AiGateway gateway;
    private final AiOperationRegistry operations;
    private final PromptRegistry prompts;
    private final ArtifactStore artifacts;

    public LearningPackService(JdbcTemplate jdbc,
                               CatalogService catalog,
                               AiGateway gateway,
                               AiOperationRegistry operations,
                               PromptRegistry prompts,
                               ArtifactStore artifacts) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.gateway = gateway;
        this.operations = operations;
        this.prompts = prompts;
        this.artifacts = artifacts;
    }

    @Transactional(readOnly = true)
    public Optional<PackView> findExisting(UUID planId, UUID learningUnitId) {
        List<Map<String, Object>> packs = jdbc.queryForList(
                "SELECT p.id, p.artifact_key, p.cache_status, p.created_at "
                        + "  FROM learning_pack p "
                        + " WHERE p.learning_unit_id = ? AND p.cache_status = 'VALID' "
                        + "   AND (p.plan_id = ? OR p.plan_id IS NULL) "
                        + " ORDER BY p.created_at DESC LIMIT 1",
                learningUnitId, planId);
        if (packs.isEmpty()) {
            return Optional.empty();
        }
        UUID packId = (UUID) packs.get(0).get("id");
        return Optional.of(new PackView(packId, (String) packs.get(0).get("artifact_key"),
                loadBlocks(packId), unitSummary(learningUnitId)));
    }

    /**
     * Generates the pack for one unit. Idempotent through the artifact key: an
     * unchanged unit, exam version and prompt version reuses the existing pack
     * and costs nothing.
     */
    @Transactional
    public Optional<PackView> generate(UUID planId, UUID learningUnitId) {
        UnitContext unit = loadUnitContext(planId, learningUnitId);
        if (unit == null) {
            return Optional.empty();
        }

        var operation = operations.require(AiOperations.PACK_GENERATE);

        // Correction A2: identity covers what the pack is about and how it was
        // produced. Change the deck, the exam version, the mapping or the prompt
        // and a new pack is generated instead of the old one being served.
        ArtifactKey key = ArtifactKey.of("LEARNING_PACK")
                .semantic("learningUnitContentHash", unit.contentHash())
                .semantic("materialRevisionId", String.valueOf(unit.materialRevisionId()))
                .semantic("certificationVersionId", unit.certificationVersionId().toString())
                .semantic("mappingVersion", unit.mappingVersion())
                .semantic("depthFlag", unit.depthFlag().name())
                .operation(operation)
                .build();

        // No cache pre-check here on purpose. The gateway owns the artifact cache
        // and records the hit; short-circuiting in front of it saved one prompt
        // render and made cache effectiveness invisible in the ledger for the
        // single most expensive operation in the product - the one worth
        // measuring. What is built below costs database reads, not money.

        List<String> mustKnow = unit.taskStatementIds().stream()
                .flatMap(id -> catalog.mustKnowItems(id).stream())
                .distinct()
                .toList();

        List<Map<String, Object>> taskInput = unit.taskStatements();
        String sourceText = loadSourceText(unit);

        String structuredInput = Json.write(new LinkedHashMap<>(Map.of(
                "title", unit.title(),
                "pageStart", unit.pageStart(),
                "pageEnd", unit.pageEnd(),
                "sourceText", sourceText,
                "mustKnow", mustKnow,
                "taskStatements", taskInput,
                "hasSignificantVisual", unit.hasSignificantVisual(),
                "testMarker", unit.title().toLowerCase())));

        String prompt = prompts.render(
                prompts.load(operation.id(), operation.promptVersion()),
                Map.of(
                        "examCode", unit.examCode(),
                        "pageStart", String.valueOf(unit.pageStart()),
                        "pageEnd", String.valueOf(unit.pageEnd()),
                        "taskStatements", Json.write(taskInput),
                        "mustKnow", String.join("\n- ", mustKnow),
                        "sourceText", sourceText,
                        "targetWords", unit.depthFlag() == DepthFlag.CONDENSED ? "500" : "900",
                        "visualNote", unit.hasSignificantVisual()
                                ? "- Phần này có nhiều sơ đồ; hãy nhắc người học mở slide gốc."
                                : ""));

        AiResult result = gateway.execute(operation.id(), planId, key, prompt, structuredInput);

        String payload;
        String source = "generated";
        // The gateway folds the generating model into the key, so the effective
        // key it returns is the one the artifact is actually stored under. Using
        // the locally built key here would file the pack under an identity no
        // artifact has.
        String effectiveKey;
        if (result instanceof AiResult.Ok ok) {
            payload = ok.payload();
            effectiveKey = ok.artifactKey();
            source = ok.cacheHit() ? "cache" : "generated";
        } else if (result instanceof AiResult.Degraded degraded) {
            payload = degraded.payload();
            effectiveKey = degraded.artifactKey();
        } else {
            log.warn("pack generation failed for unit {}: {}", learningUnitId, result);
            return Optional.empty();
        }

        UUID packId = persistPack(planId, unit, effectiveKey, payload, source, 0, 0, 0);
        extractGlossary(planId, packId, payload);

        return Optional.of(new PackView(packId, effectiveKey, loadBlocks(packId),
                unitSummary(learningUnitId)));
    }

    private UUID persistPack(UUID planId, UnitContext unit, String artifactKey, String payload,
                             String source, int tokensIn, int tokensOut, int costCents) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM learning_pack WHERE artifact_key = ?", artifactKey);
        if (!existing.isEmpty()) {
            return (UUID) existing.get(0).get("id");
        }

        var operation = operations.require(AiOperations.PACK_GENERATE);
        UUID packId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO learning_pack (id, artifact_key, learning_unit_id, "
                        + " certification_version_id, plan_id, cache_status, depth_flag, model, "
                        + " prompt_version, output_schema_version, model_config_version, "
                        + " tokens_in, tokens_out, cost_cents) "
                        + "VALUES (?,?,?,?,?, 'VALID', ?,?,?,?,?,?,?,?)",
                packId, artifactKey, unit.learningUnitId(), unit.certificationVersionId(), planId,
                unit.depthFlag().name(), source, operation.promptVersion(),
                operation.outputSchemaVersion(), operation.modelConfigVersion(),
                tokensIn, tokensOut, costCents);

        persistBlocks(packId, unit, payload);
        return packId;
    }

    /**
     * The page range a block claims, or nulls when it claims none.
     *
     * <p>Extracted and package-private so the null case is testable. It used to be
     * a ternary, and mixing {@code Integer} with {@code int} in one conditional
     * makes the whole expression {@code int} - so the null branch unboxed and
     * threw. A supplement block legitimately carries no page range, the
     * deterministic fake always emitted one, and the first real model that did
     * not threw away a lesson the ledger had already paid for.
     */
    static SourcePages sourcePages(JsonNode block) {
        Integer start = null;
        if (block.hasNonNull("sourcePageStart")) {
            start = block.path("sourcePageStart").asInt();
        }
        Integer end = start;
        if (block.hasNonNull("sourcePageEnd")) {
            end = block.path("sourcePageEnd").asInt();
        }
        return new SourcePages(start, end);
    }

    record SourcePages(Integer start, Integer end) {
    }

    private void persistBlocks(UUID packId, UnitContext unit, String payload) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            int index = 0;
            for (JsonNode block : root.path("blocks")) {
                String origin = block.path("origin").asText("FROM_MATERIAL");
                SourcePages pages = sourcePages(block);
                Integer pageStart = pages.start();
                Integer pageEnd = pages.end();

                // The schema forbids a material-origin block without provenance;
                // rather than lose the content, downgrade it to a supplement.
                if ("FROM_MATERIAL".equals(origin) && pageStart == null) {
                    origin = "AI_SUPPLEMENT";
                }

                jdbc.update(
                        "INSERT INTO content_block (id, learning_pack_id, order_index, block_type, "
                                + " payload, origin, exam_relevance, source_material_revision_id, "
                                + " source_page_start, source_page_end, source_span) "
                                + "VALUES (?,?,?,?,?::jsonb,?,?,?,?,?,?)",
                        UUID.randomUUID(), packId, index++, block.path("type").asText("concept"),
                        block.path("payload").toString(), origin,
                        block.hasNonNull("examRelevance") ? block.path("examRelevance").asText() : null,
                        unit.materialRevisionId(), pageStart, pageEnd,
                        block.hasNonNull("sourceSpan") ? block.path("sourceSpan").asText() : null);
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot persist pack blocks", e);
        }
    }

    /** One explanation per term per plan; later mentions link back to the first. */
    private void extractGlossary(UUID planId, UUID packId, String payload) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            for (JsonNode block : root.path("blocks")) {
                if (!"keyword".equals(block.path("type").asText())) {
                    continue;
                }
                JsonNode p = block.path("payload");
                String term = p.path("term").asText("");
                if (term.isBlank()) {
                    continue;
                }
                jdbc.update(
                        "INSERT INTO glossary_entry (id, plan_id, term, first_pack_id, simple_text, "
                                + " technical_text, example_text, exam_note) VALUES (?,?,?,?,?,?,?,?) "
                                + "ON CONFLICT (plan_id, term) DO NOTHING",
                        UUID.randomUUID(), planId, term, packId,
                        p.path("simple").asText(null), p.path("technical").asText(null),
                        p.path("example").asText(null), p.path("examNote").asText(null));
            }
        } catch (Exception e) {
            log.debug("glossary extraction skipped: {}", e.toString());
        }
    }

    @Transactional
    public void flagBlock(UUID userId, UUID blockId, String reason, String note) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.artifact_key FROM content_block b "
                        + "  JOIN learning_pack p ON p.id = b.learning_pack_id WHERE b.id = ?", blockId);
        String artifactKey = rows.isEmpty() ? null : (String) rows.get(0).get("artifact_key");

        jdbc.update(
                "INSERT INTO content_flag (id, user_id, block_id, reason, note, invalidated_artifact_key) "
                        + "VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), userId, blockId, reason, note, artifactKey);

        // Risk AR-8: without this, the cache turns one bad generation into a
        // permanent one for every learner who follows.
        if (artifactKey != null) {
            jdbc.update("UPDATE learning_pack SET cache_status = 'INVALIDATED', "
                    + " invalidated_reason = ? WHERE artifact_key = ?", reason, artifactKey);
            artifacts.invalidate(artifactKey, reason);
        }
    }

    // -------------------------------------------------------------- loading

    private List<ContentBlockView> loadBlocks(UUID packId) {
        return jdbc.query(
                "SELECT id, order_index, block_type, payload, origin, exam_relevance, "
                        + "       source_page_start, source_page_end, source_span "
                        + "  FROM content_block WHERE learning_pack_id = ? ORDER BY order_index",
                (rs, n) -> new ContentBlockView(
                        rs.getObject("id", UUID.class),
                        rs.getInt("order_index"),
                        rs.getString("block_type"),
                        Json.read(rs.getString("payload"), Map.class),
                        rs.getString("origin"),
                        rs.getString("exam_relevance"),
                        (Integer) rs.getObject("source_page_start"),
                        (Integer) rs.getObject("source_page_end"),
                        rs.getString("source_span")),
                packId);
    }

    private UnitSummary unitSummary(UUID learningUnitId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT u.id, u.title, u.page_start, u.page_end, u.material_revision_id, "
                        + "       r.material_id "
                        + "  FROM learning_unit u "
                        + "  LEFT JOIN material_revision r ON r.id = u.material_revision_id "
                        + " WHERE u.id = ?", learningUnitId);
        return new UnitSummary(
                (UUID) row.get("id"), (String) row.get("title"),
                (Integer) row.get("page_start"), (Integer) row.get("page_end"),
                (UUID) row.get("material_id"));
    }

    private UnitContext loadUnitContext(UUID planId, UUID learningUnitId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id, u.title, u.page_start, u.page_end, u.content_hash, "
                        + "       u.material_revision_id, pu.depth_flag, "
                        + "       p.certification_version_id, p.mapping_version, cv.exam_code "
                        + "  FROM learning_unit u "
                        + "  JOIN plan_unit pu ON pu.learning_unit_id = u.id AND pu.plan_id = ? "
                        + "  JOIN study_plan p ON p.id = pu.plan_id "
                        + "  JOIN certification_version cv ON cv.id = p.certification_version_id "
                        + " WHERE u.id = ?", planId, learningUnitId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        UUID revisionId = (UUID) row.get("material_revision_id");
        int pageStart = row.get("page_start") == null ? 1 : ((Number) row.get("page_start")).intValue();
        int pageEnd = row.get("page_end") == null ? pageStart : ((Number) row.get("page_end")).intValue();

        List<Map<String, Object>> tasks = jdbc.queryForList(
                "SELECT DISTINCT t.id, t.code, t.title, d.title AS domain_title "
                        + "  FROM unit_exam_mapping m "
                        + "  JOIN task_statement t ON t.id = m.task_statement_id "
                        + "  JOIN exam_domain d ON d.id = t.exam_domain_id "
                        + " WHERE m.learning_unit_id = ? AND m.certification_version_id = ?",
                learningUnitId, row.get("certification_version_id"));

        List<UUID> taskIds = tasks.stream().map(t -> (UUID) t.get("id")).toList();
        List<Map<String, Object>> taskInput = tasks.stream()
                .map(t -> Map.of("id", String.valueOf(t.get("id")), "code", String.valueOf(t.get("code")),
                        "title", String.valueOf(t.get("title")),
                        "domain", String.valueOf(t.get("domain_title"))))
                .map(m -> (Map<String, Object>) new LinkedHashMap<String, Object>(m))
                .toList();

        Boolean hasVisual = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM material_page WHERE material_revision_id = ? "
                        + " AND page_no BETWEEN ? AND ? AND has_significant_visual)",
                Boolean.class, revisionId, pageStart, pageEnd);

        return new UnitContext(
                learningUnitId, (String) row.get("title"), pageStart, pageEnd,
                (String) row.get("content_hash"), revisionId,
                (UUID) row.get("certification_version_id"),
                row.get("mapping_version") == null ? "map-v1" : (String) row.get("mapping_version"),
                DepthFlag.valueOf((String) row.get("depth_flag")),
                (String) row.get("exam_code"),
                taskIds, taskInput, Boolean.TRUE.equals(hasVisual));
    }

    private String loadSourceText(UnitContext unit) {
        List<String> parts = jdbc.queryForList(
                "SELECT COALESCE(title_guess, '') || E'\\n' || text "
                        + "  || CASE WHEN notes_text IS NULL THEN '' "
                        + "          ELSE E'\\n[Ghi chú giảng viên] ' || notes_text END "
                        + "  FROM material_page WHERE material_revision_id = ? "
                        + "   AND page_no BETWEEN ? AND ? ORDER BY page_no",
                String.class, unit.materialRevisionId(), unit.pageStart(), unit.pageEnd());
        String joined = String.join("\n\n", parts);
        // Cap the context sent to the model: a unit is 18-30 pages, which is well
        // inside any window, and an unbounded prompt is an unbounded bill.
        return joined.length() > 24_000 ? joined.substring(0, 24_000) : joined;
    }

    // --------------------------------------------------------------- records

    public record UnitContext(UUID learningUnitId, String title, int pageStart, int pageEnd,
                              String contentHash, UUID materialRevisionId,
                              UUID certificationVersionId, String mappingVersion,
                              DepthFlag depthFlag, String examCode,
                              List<UUID> taskStatementIds,
                              List<Map<String, Object>> taskStatements,
                              boolean hasSignificantVisual) {
    }

    public record ContentBlockView(UUID id, int orderIndex, String type, Map<String, Object> payload,
                                   String origin, String examRelevance, Integer sourcePageStart,
                                   Integer sourcePageEnd, String sourceSpan) {
    }

    public record UnitSummary(UUID learningUnitId, String title, Integer pageStart, Integer pageEnd,
                              UUID materialId) {
    }

    public record PackView(UUID packId, String artifactKey, List<ContentBlockView> blocks,
                           UnitSummary unit) {
    }
}
