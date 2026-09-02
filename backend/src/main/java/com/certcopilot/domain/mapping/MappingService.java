package com.certcopilot.domain.mapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.certcopilot.domain.catalog.CatalogService;
import com.certcopilot.platform.ai.AiGateway;
import com.certcopilot.platform.ai.AiOperationRegistry;
import com.certcopilot.platform.ai.AiOperations;
import com.certcopilot.platform.ai.AiResult;
import com.certcopilot.platform.ai.ArtifactKey;
import com.certcopilot.platform.ai.PromptRegistry;
import com.certcopilot.shared.CoverageStatus;
import com.certcopilot.shared.ExamRelevance;
import com.certcopilot.shared.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connects learning units to the official exam scope, then computes coverage.
 *
 * <p>Keyed by (material revision, certification version, mapping version) so the
 * same material mapped against a second certification reuses every unit and only
 * pays for the mapping - which is the point of keeping relevance out of the unit
 * itself (correction A1).
 *
 * <p>Mapping is the model's job because it is a semantic question. Coverage is
 * arithmetic over the result and never involves a model.
 */
@Service
public class MappingService {

    /** Mirrors {@code AiProvenance.Source.SYNTHETIC}; kept as a value so the domain stays free of the platform enum. */
    private static final String SYNTHETIC = "SYNTHETIC";

    private static final Logger log = LoggerFactory.getLogger(MappingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Bumped when the mapping algorithm or thresholds change. */
    public static final String MAPPING_VERSION = "map-v1";

    /** Below this the mapping is treated as a guess and left out. */
    private static final double MIN_CONFIDENCE = 0.45;

    /** Below this share of mapped units the material probably is not for this exam. */
    private static final double SUSPICIOUS_MAPPING_RATE = 0.40;

    private final JdbcTemplate jdbc;
    private final CatalogService catalog;
    private final AiGateway gateway;
    private final AiOperationRegistry operations;
    private final PromptRegistry prompts;

    public MappingService(JdbcTemplate jdbc,
                          CatalogService catalog,
                          AiGateway gateway,
                          AiOperationRegistry operations,
                          PromptRegistry prompts) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.gateway = gateway;
        this.operations = operations;
        this.prompts = prompts;
    }

    /**
     * Maps every unit of a revision against a certification, then recomputes
     * coverage. Idempotent: an existing mapping for the same key is reused.
     */
    @Transactional
    public MappingOutcome mapRevision(UUID planId, UUID revisionId, UUID certificationVersionId) {
        List<UnitRow> units = loadUnits(revisionId);
        if (units.isEmpty()) {
            return new MappingOutcome(0, 0, 0, false);
        }

        Integer already = jdbc.queryForObject(
                "SELECT count(*) FROM unit_exam_mapping m JOIN learning_unit u ON u.id = m.learning_unit_id "
                        + " WHERE u.material_revision_id = ? AND m.certification_version_id = ? "
                        + "   AND m.mapping_version = ?",
                Integer.class, revisionId, certificationVersionId, MAPPING_VERSION);
        if (already != null && already > 0) {
            log.debug("mapping already present for revision {} / version {}",
                    revisionId, certificationVersionId);
            computeCoverage(revisionId, certificationVersionId);
            return summarise(revisionId, certificationVersionId, units.size());
        }

        List<CatalogService.TaskWeight> tasks = catalog.taskWeights(certificationVersionId);
        var operation = operations.require(AiOperations.TOPIC_TO_TASK);

        List<Map<String, Object>> taskInput = new ArrayList<>();
        Map<UUID, CatalogService.TaskStatementView> taskById = new HashMap<>();
        for (CatalogService.DomainView domain : catalog.blueprint(certificationVersionId).domains()) {
            for (CatalogService.TaskStatementView task : domain.taskStatements()) {
                taskById.put(task.id(), task);
                taskInput.add(Map.of(
                        "id", task.id().toString(),
                        "code", task.code(),
                        "title", task.title(),
                        "domain", domain.title(),
                        "domainWeight", domain.weightPercent()));
            }
        }

        List<Map<String, Object>> unitInput = units.stream()
                .map(u -> Map.<String, Object>of(
                        "id", u.id().toString(),
                        "title", u.title(),
                        "pageStart", u.pageStart() == null ? 0 : u.pageStart(),
                        "pageEnd", u.pageEnd() == null ? 0 : u.pageEnd(),
                        "sample", u.sample() == null ? "" : u.sample()))
                .toList();

        String structuredInput = Json.write(Map.of(
                "taskStatements", taskInput, "units", unitInput));
        String prompt = prompts.render(
                prompts.load(operation.id(), operation.promptVersion()),
                Map.of("taskStatements", Json.write(taskInput), "units", Json.write(unitInput)));

        ArtifactKey key = ArtifactKey.of("UNIT_EXAM_MAPPING")
                .semantic("materialRevisionId", revisionId.toString())
                .semantic("certificationVersionId", certificationVersionId.toString())
                .semantic("mappingVersion", MAPPING_VERSION)
                .semantic("unitCount", String.valueOf(units.size()))
                .operation(operation)
                .build();

        AiResult result = gateway.execute(operation.id(), planId, key, prompt, structuredInput);

        int stored = 0;
        Set<UUID> mappedUnits = new HashSet<>();
        if (result instanceof AiResult.Ok ok) {
            stored = persistMappings(ok.payload(), certificationVersionId, mappedUnits, units);
        } else if (result instanceof AiResult.Degraded degraded) {
            stored = persistMappings(degraded.payload(), certificationVersionId, mappedUnits, units);
        } else {
            log.warn("mapping failed for revision {}: {}", revisionId, result);
        }

        computeCoverage(revisionId, certificationVersionId);
        log.info("mapped revision {} against {}: {} mappings across {}/{} units",
                revisionId, certificationVersionId, stored, mappedUnits.size(), units.size());

        return summarise(revisionId, certificationVersionId, units.size());
    }

    private int persistMappings(String payload, UUID certificationVersionId,
                                Set<UUID> mappedUnits, List<UnitRow> units) {
        Set<UUID> validUnitIds = new HashSet<>();
        units.forEach(u -> validUnitIds.add(u.id()));

        int stored = 0;
        try {
            JsonNode root = MAPPER.readTree(payload);
            for (JsonNode node : root.path("mappings")) {
                double confidence = node.path("confidence").asDouble(0);
                if (confidence < MIN_CONFIDENCE) {
                    // Leaving a unit unmapped is the honest outcome; forcing a
                    // low-confidence match corrupts coverage silently.
                    continue;
                }
                UUID unitId = UUID.fromString(node.path("unitId").asText());
                if (!validUnitIds.contains(unitId)) {
                    continue;
                }
                UUID taskId = UUID.fromString(node.path("taskStatementId").asText());

                jdbc.update(
                        "INSERT INTO unit_exam_mapping (id, learning_unit_id, certification_version_id, "
                                + " task_statement_id, mapping_version, relevance, confidence, rationale, method) "
                                + "VALUES (?,?,?,?,?,?,?,?, 'AI_EXTRACTED') "
                                + "ON CONFLICT (learning_unit_id, certification_version_id, "
                                + "             task_statement_id, mapping_version) DO NOTHING",
                        UUID.randomUUID(), unitId, certificationVersionId, taskId, MAPPING_VERSION,
                        node.path("relevance").asText("MEDIUM"), confidence,
                        node.path("rationale").asText(""));
                mappedUnits.add(unitId);
                stored++;
            }
        } catch (Exception e) {
            log.warn("cannot parse mapping payload: {}", e.toString());
        }
        return stored;
    }

    /**
     * Coverage: a deterministic set operation, no model involved.
     *
     * <p>Measured at task-statement level. Domain is too coarse to act on and
     * knowledge items are too noisy; a task statement is the unit the exam guide
     * itself describes.
     */
    @Transactional
    public void computeCoverage(UUID revisionId, UUID certificationVersionId) {
        jdbc.update("DELETE FROM coverage_assessment WHERE material_revision_id = ? "
                        + " AND certification_version_id = ? AND mapping_version = ?",
                revisionId, certificationVersionId, MAPPING_VERSION);

        List<CatalogService.TaskWeight> tasks = catalog.taskWeights(certificationVersionId);

        for (CatalogService.TaskWeight task : tasks) {
            List<Map<String, Object>> evidence = jdbc.queryForList(
                    "SELECT m.learning_unit_id, m.relevance, m.confidence, u.base_effort_minutes "
                            + "  FROM unit_exam_mapping m JOIN learning_unit u ON u.id = m.learning_unit_id "
                            + " WHERE u.material_revision_id = ? AND m.certification_version_id = ? "
                            + "   AND m.mapping_version = ? AND m.task_statement_id = ?",
                    revisionId, certificationVersionId, MAPPING_VERSION, task.taskStatementId());

            CoverageStatus status;
            if (evidence.isEmpty()) {
                status = CoverageStatus.NOT_COVERED;
            } else {
                int totalMinutes = evidence.stream()
                        .mapToInt(e -> ((Number) e.get("base_effort_minutes")).intValue()).sum();
                double bestConfidence = evidence.stream()
                        .mapToDouble(e -> ((Number) e.get("confidence")).doubleValue()).max().orElse(0);
                // Thin means mapped but barely: not enough material, or a weak
                // match. Both deserve to be visible rather than counted as done.
                status = (totalMinutes >= 25 && bestConfidence >= 0.6)
                        ? CoverageStatus.COVERED : CoverageStatus.PARTIAL;
            }

            jdbc.update(
                    "INSERT INTO coverage_assessment (id, material_revision_id, certification_version_id, "
                            + " mapping_version, task_statement_id, status, evidence) "
                            + "VALUES (?,?,?,?,?,?,?::jsonb)",
                    UUID.randomUUID(), revisionId, certificationVersionId, MAPPING_VERSION,
                    task.taskStatementId(), status.name(),
                    Json.write(Map.of("unitCount", evidence.size())));
        }
    }

    /**
     * @param contentProvenance where the mappings this report counts came from.
     *                          A percentage computed from fixture output must not
     *                          be presented as a measurement, so the report says
     *                          which it is rather than leaving the caller to guess.
     */
    @Transactional(readOnly = true)
    public CoverageReport coverageReport(UUID revisionId, UUID certificationVersionId,
                                         String contentProvenance) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.code AS domain_code, d.title AS domain_title, d.weight_percent, "
                        + "       t.code AS task_code, t.title AS task_title, t.id AS task_id, "
                        + "       COALESCE(c.status, 'NOT_COVERED') AS status "
                        + "  FROM exam_domain d "
                        + "  JOIN task_statement t ON t.exam_domain_id = d.id "
                        + "  LEFT JOIN coverage_assessment c "
                        + "         ON c.task_statement_id = t.id "
                        + "        AND c.material_revision_id = ? "
                        + "        AND c.certification_version_id = ? "
                        + "        AND c.mapping_version = ? "
                        + " WHERE d.certification_version_id = ? "
                        + " ORDER BY d.order_index, t.order_index",
                revisionId, certificationVersionId, MAPPING_VERSION, certificationVersionId);

        Map<String, DomainCoverageBuilder> byDomain = new LinkedHashMap<>();
        List<TaskCoverage> gaps = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String domainCode = (String) row.get("domain_code");
            DomainCoverageBuilder builder = byDomain.computeIfAbsent(domainCode,
                    k -> new DomainCoverageBuilder(domainCode, (String) row.get("domain_title"),
                            ((Number) row.get("weight_percent")).intValue()));
            CoverageStatus status = CoverageStatus.valueOf((String) row.get("status"));
            builder.add(status);

            if (status != CoverageStatus.COVERED) {
                gaps.add(new TaskCoverage(
                        (UUID) row.get("task_id"), (String) row.get("task_code"),
                        (String) row.get("task_title"), domainCode, status));
            }
        }

        List<DomainCoverage> domains = byDomain.values().stream()
                .map(DomainCoverageBuilder::build).toList();

        double weighted = domains.stream()
                .mapToDouble(d -> d.coveredRatio() * d.weightPercent()).sum();

        return new CoverageReport(Math.round(weighted), domains, gaps,
                contentProvenance, !SYNTHETIC.equals(contentProvenance));
    }

    private MappingOutcome summarise(UUID revisionId, UUID certificationVersionId, int unitCount) {
        Integer mappedUnits = jdbc.queryForObject(
                "SELECT count(DISTINCT m.learning_unit_id) FROM unit_exam_mapping m "
                        + "  JOIN learning_unit u ON u.id = m.learning_unit_id "
                        + " WHERE u.material_revision_id = ? AND m.certification_version_id = ? "
                        + "   AND m.mapping_version = ?",
                Integer.class, revisionId, certificationVersionId, MAPPING_VERSION);
        Integer mappings = jdbc.queryForObject(
                "SELECT count(*) FROM unit_exam_mapping m "
                        + "  JOIN learning_unit u ON u.id = m.learning_unit_id "
                        + " WHERE u.material_revision_id = ? AND m.certification_version_id = ? "
                        + "   AND m.mapping_version = ?",
                Integer.class, revisionId, certificationVersionId, MAPPING_VERSION);

        int mapped = mappedUnits == null ? 0 : mappedUnits;
        double rate = unitCount == 0 ? 0 : (double) mapped / unitCount;

        // A very low rate usually means the learner uploaded material for a
        // different certification. Better to say so than to build a plan on it.
        return new MappingOutcome(unitCount, mapped, mappings == null ? 0 : mappings,
                rate < SUSPICIOUS_MAPPING_RATE);
    }

    /** Resolved relevance per unit: the strongest mapping wins. */
    @Transactional(readOnly = true)
    public Map<UUID, ExamRelevance> resolvedRelevance(UUID revisionId, UUID certificationVersionId) {
        Map<UUID, ExamRelevance> result = new HashMap<>();
        jdbc.queryForList(
                "SELECT m.learning_unit_id, m.relevance FROM unit_exam_mapping m "
                        + "  JOIN learning_unit u ON u.id = m.learning_unit_id "
                        + " WHERE u.material_revision_id = ? AND m.certification_version_id = ? "
                        + "   AND m.mapping_version = ?",
                revisionId, certificationVersionId, MAPPING_VERSION)
                .forEach(row -> {
                    UUID unitId = (UUID) row.get("learning_unit_id");
                    ExamRelevance candidate = ExamRelevance.valueOf((String) row.get("relevance"));
                    result.merge(unitId, candidate,
                            (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
                });
        return result;
    }

    private List<UnitRow> loadUnits(UUID revisionId) {
        return jdbc.query(
                "SELECT u.id, u.title, u.page_start, u.page_end, "
                        + "       (SELECT string_agg(left(p.text, 200), ' ') FROM material_page p "
                        + "         WHERE p.material_revision_id = u.material_revision_id "
                        + "           AND p.page_no BETWEEN u.page_start AND u.page_end) AS sample "
                        + "  FROM learning_unit u WHERE u.material_revision_id = ? ORDER BY u.sequence",
                (rs, n) -> new UnitRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        (Integer) rs.getObject("page_start"),
                        (Integer) rs.getObject("page_end"),
                        rs.getString("sample")),
                revisionId);
    }

    private record UnitRow(UUID id, String title, Integer pageStart, Integer pageEnd, String sample) {
    }

    public record MappingOutcome(int unitCount, int mappedUnitCount, int mappingCount,
                                 boolean suspiciouslyLowMappingRate) {
    }

    public record TaskCoverage(UUID taskStatementId, String code, String title,
                               String domainCode, CoverageStatus status) {
    }

    public record DomainCoverage(String code, String title, int weightPercent,
                                 int taskCount, int coveredCount, int partialCount,
                                 int notCoveredCount, double coveredRatio) {
    }

    /**
     * @param contentProvenance  MODEL, SYNTHETIC or NONE
     * @param coverageEvaluated  false when the numbers come from fixture output;
     *                           the client shows the mapping as unevaluated rather
     *                           than printing a percentage nobody should act on
     */
    public record CoverageReport(long weightedCoveragePercent,
                                 List<DomainCoverage> domains,
                                 List<TaskCoverage> gaps,
                                 String contentProvenance,
                                 boolean coverageEvaluated) {
    }

    private static final class DomainCoverageBuilder {
        private final String code;
        private final String title;
        private final int weight;
        private int total;
        private int covered;
        private int partial;
        private int notCovered;

        DomainCoverageBuilder(String code, String title, int weight) {
            this.code = code;
            this.title = title;
            this.weight = weight;
        }

        void add(CoverageStatus status) {
            total++;
            switch (status) {
                case COVERED -> covered++;
                case PARTIAL -> partial++;
                case NOT_COVERED -> notCovered++;
            }
        }

        DomainCoverage build() {
            // Partial counts as half: it is real material, just thin.
            double ratio = total == 0 ? 0 : (covered + partial * 0.5) / total;
            return new DomainCoverage(code, title, weight, total, covered, partial, notCovered, ratio);
        }
    }
}
