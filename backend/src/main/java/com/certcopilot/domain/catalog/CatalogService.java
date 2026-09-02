package com.certcopilot.domain.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to the curated certification catalog.
 *
 * <p>Curated rather than discovered at runtime: domain weights drive the entire
 * schedule, and a wrong weight produces a wrong plan the learner has no way to
 * notice. At a catalogue size of one, human verification is both cheaper and
 * more accurate than an extraction pipeline.
 *
 * <p>Nothing here names a provider. AIF-C01 is seed data.
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final JdbcTemplate jdbc;

    public CatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CertificationSummary> search(String query) {
        String like = query == null || query.isBlank() ? "%" : "%" + query.strip().toLowerCase() + "%";
        return jdbc.query(
                "SELECT cv.id, c.provider, c.name, cv.exam_code, cv.version_label, "
                        + "       cv.duration_minutes, cv.question_count, cv.passing_score "
                        + "  FROM certification_version cv "
                        + "  JOIN certification c ON c.id = cv.certification_id "
                        + " WHERE cv.status = 'PUBLISHED' "
                        + "   AND (lower(c.name) LIKE ? OR lower(cv.exam_code) LIKE ? OR lower(c.provider) LIKE ?) "
                        + " ORDER BY c.provider, c.name",
                (rs, n) -> new CertificationSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("provider"),
                        rs.getString("name"),
                        rs.getString("exam_code"),
                        rs.getString("version_label"),
                        (Integer) rs.getObject("duration_minutes"),
                        (Integer) rs.getObject("question_count"),
                        (Integer) rs.getObject("passing_score")),
                like, like, like);
    }

    public Blueprint blueprint(UUID certificationVersionId) {
        List<Map<String, Object>> head = jdbc.queryForList(
                "SELECT cv.id, c.provider, c.name, cv.exam_code, cv.version_label, cv.official_url, "
                        + "       cv.exam_guide_url, cv.duration_minutes, cv.question_count, cv.passing_score "
                        + "  FROM certification_version cv "
                        + "  JOIN certification c ON c.id = cv.certification_id "
                        + " WHERE cv.id = ?",
                certificationVersionId);
        if (head.isEmpty()) {
            throw new IllegalArgumentException("unknown certification version " + certificationVersionId);
        }
        Map<String, Object> h = head.get(0);

        List<Map<String, Object>> taskRows = jdbc.queryForList(
                "SELECT d.id AS domain_id, d.code AS domain_code, d.title AS domain_title, "
                        + "       d.weight_percent, d.order_index AS domain_order, "
                        + "       t.id AS task_id, t.code AS task_code, t.title AS task_title, "
                        + "       t.order_index AS task_order "
                        + "  FROM exam_domain d "
                        + "  LEFT JOIN task_statement t ON t.exam_domain_id = d.id "
                        + " WHERE d.certification_version_id = ? "
                        + " ORDER BY d.order_index, t.order_index",
                certificationVersionId);

        Map<UUID, List<TaskStatementView>> tasksByDomain = taskRows.stream()
                .filter(r -> r.get("task_id") != null)
                .collect(Collectors.groupingBy(
                        r -> (UUID) r.get("domain_id"),
                        java.util.LinkedHashMap::new,
                        Collectors.mapping(r -> new TaskStatementView(
                                (UUID) r.get("task_id"),
                                (String) r.get("task_code"),
                                (String) r.get("task_title")), Collectors.toList())));

        List<DomainView> domains = taskRows.stream()
                .map(r -> (UUID) r.get("domain_id"))
                .distinct()
                .map(domainId -> {
                    Map<String, Object> first = taskRows.stream()
                            .filter(r -> domainId.equals(r.get("domain_id")))
                            .findFirst().orElseThrow();
                    return new DomainView(
                            domainId,
                            (String) first.get("domain_code"),
                            (String) first.get("domain_title"),
                            ((Number) first.get("weight_percent")).intValue(),
                            tasksByDomain.getOrDefault(domainId, List.of()));
                })
                .toList();

        List<SourceRefView> sources = jdbc.query(
                "SELECT field, url, doc_title, method, verified_by FROM source_ref "
                        + " WHERE entity_type = 'certification_version' AND entity_id = ?",
                (rs, n) -> new SourceRefView(
                        rs.getString("field"), rs.getString("url"),
                        rs.getString("doc_title"), rs.getString("method"),
                        rs.getString("verified_by")),
                certificationVersionId);

        return new Blueprint(
                (UUID) h.get("id"),
                (String) h.get("provider"),
                (String) h.get("name"),
                (String) h.get("exam_code"),
                (String) h.get("version_label"),
                (String) h.get("official_url"),
                (String) h.get("exam_guide_url"),
                (Integer) h.get("duration_minutes"),
                (Integer) h.get("question_count"),
                (Integer) h.get("passing_score"),
                domains,
                sources);
    }

    /** Task statement ids with their domain weight, used by planning and exam assembly. */
    public List<TaskWeight> taskWeights(UUID certificationVersionId) {
        return jdbc.query(
                "SELECT t.id AS task_id, d.id AS domain_id, d.code AS domain_code, "
                        + "       d.title AS domain_title, d.weight_percent "
                        + "  FROM task_statement t JOIN exam_domain d ON d.id = t.exam_domain_id "
                        + " WHERE d.certification_version_id = ? ORDER BY d.order_index, t.order_index",
                (rs, n) -> new TaskWeight(
                        rs.getObject("task_id", UUID.class),
                        rs.getObject("domain_id", UUID.class),
                        rs.getString("domain_code"),
                        rs.getString("domain_title"),
                        rs.getInt("weight_percent")),
                certificationVersionId);
    }

    public List<String> mustKnowItems(UUID taskStatementId) {
        return jdbc.queryForList(
                "SELECT text FROM knowledge_item WHERE task_statement_id = ? AND is_must_know "
                        + " ORDER BY order_index", String.class, taskStatementId);
    }

    public List<OfficialResourceView> resourcesFor(UUID taskStatementId) {
        return jdbc.query(
                "SELECT title, url, resource_type FROM official_resource WHERE task_statement_id = ?",
                (rs, n) -> new OfficialResourceView(
                        rs.getString("title"), rs.getString("url"), rs.getString("resource_type")),
                taskStatementId);
    }

    public boolean versionExists(UUID certificationVersionId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM certification_version WHERE id = ? AND status = 'PUBLISHED'",
                Integer.class, certificationVersionId);
        return n != null && n > 0;
    }

    public record CertificationSummary(UUID certificationVersionId, String provider, String name,
                                       String examCode, String versionLabel, Integer durationMinutes,
                                       Integer questionCount, Integer passingScore) {
    }

    public record TaskStatementView(UUID id, String code, String title) {
    }

    public record DomainView(UUID id, String code, String title, int weightPercent,
                             List<TaskStatementView> taskStatements) {
    }

    public record SourceRefView(String field, String url, String docTitle, String method,
                                String verifiedBy) {
    }

    public record OfficialResourceView(String title, String url, String resourceType) {
    }

    public record TaskWeight(UUID taskStatementId, UUID domainId, String domainCode,
                             String domainTitle, int domainWeightPercent) {
    }

    public record Blueprint(UUID certificationVersionId, String provider, String name,
                            String examCode, String versionLabel, String officialUrl,
                            String examGuideUrl, Integer durationMinutes, Integer questionCount,
                            Integer passingScore, List<DomainView> domains,
                            List<SourceRefView> sources) {
    }
}
