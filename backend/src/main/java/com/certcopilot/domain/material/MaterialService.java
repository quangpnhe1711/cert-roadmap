package com.certcopilot.domain.material;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.platform.jobs.JobQueue;
import com.certcopilot.platform.storage.ObjectStoragePort;
import com.certcopilot.platform.storage.StorageProperties;
import com.certcopilot.shared.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upload, ownership and revision lifecycle for learner material.
 *
 * <p>Material is mandatory (decision D2): without it the product degrades to a
 * generic roadmap, which is the thing anyone can already get for free.
 */
@Service
public class MaterialService {

    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);

    public static final String JOB_TYPE = "MATERIAL_PROCESS";

    private final JdbcTemplate jdbc;
    private final ObjectStoragePort storage;
    private final StorageProperties storageProps;
    private final JobQueue jobs;
    private final ExamDumpDetector dumpDetector;

    public MaterialService(JdbcTemplate jdbc,
                           ObjectStoragePort storage,
                           StorageProperties storageProps,
                           JobQueue jobs,
                           ExamDumpDetector dumpDetector) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.storageProps = storageProps;
        this.jobs = jobs;
        this.dumpDetector = dumpDetector;
    }

    /**
     * Stores an uploaded file, creates revision 1 and enqueues processing in the
     * same transaction so the job cannot be lost or orphaned.
     */
    @Transactional
    public UploadResult upload(UUID userId, UUID planId, String fileName,
                               String declaredMime, byte[] content) {
        if (content.length == 0) {
            throw new MaterialException("EMPTY_FILE", "file is empty");
        }
        if (content.length > storageProps.getMaxUploadBytes()) {
            throw new MaterialException("FILE_TOO_LARGE",
                    "file exceeds " + (storageProps.getMaxUploadBytes() / (1024 * 1024)) + " MB");
        }

        // Sniff the real type; the extension and the declared MIME are both
        // attacker-controlled and neither is evidence of anything.
        String mime = sniffMime(content, fileName, declaredMime);
        if (!isSupported(mime)) {
            throw new MaterialException("UNSUPPORTED_FORMAT",
                    "only PDF and PPTX are supported in this version");
        }

        ExamDumpDetector.Verdict dump = dumpDetector.inspect(fileName, content);
        if (dump.rejected()) {
            log.warn("rejected suspected exam dump upload from user {}: {}", userId, dump.reason());
            throw new MaterialException("EXAM_DUMP_REJECTED", dump.reason());
        }

        String contentHash = sha256(content);

        // Scoped to the owner, not global: two learners uploading the same course
        // file is ordinary (decision D9).
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM material WHERE user_id = ? AND source_content_hash = ?",
                userId, contentHash);
        if (!existing.isEmpty()) {
            UUID materialId = (UUID) existing.get(0).get("id");
            log.info("user {} re-uploaded identical material {}", userId, materialId);
            return reprocess(userId, planId, materialId);
        }

        UUID materialId = UUID.randomUUID();
        String storageKey = "materials/%s/%s/%s".formatted(userId, materialId, contentHash);
        storage.put(storageKey, new ByteArrayInputStream(content), content.length, mime);

        jdbc.update(
                "INSERT INTO material (id, user_id, file_name, mime, size_bytes, storage_key, "
                        + " viewer_key, source_content_hash, role) VALUES (?,?,?,?,?,?,?,?, 'PRIMARY_COURSE')",
                materialId, userId, fileName, mime, content.length, storageKey,
                isPdf(mime) ? storageKey : null, contentHash);

        UUID revisionId = createRevision(materialId, contentHash, 1);
        linkToPlan(planId, revisionId);
        enqueueProcessing(revisionId);

        return new UploadResult(materialId, revisionId, mime, content.length);
    }

    /** Creates a new revision of an existing material and reprocesses it. */
    @Transactional
    public UploadResult reprocess(UUID userId, UUID planId, UUID materialId) {
        Map<String, Object> material = requireOwned(userId, materialId);
        Integer maxRevision = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision_no), 0) FROM material_revision WHERE material_id = ?",
                Integer.class, materialId);

        UUID revisionId = createRevision(materialId,
                (String) material.get("source_content_hash"), (maxRevision == null ? 0 : maxRevision) + 1);
        linkToPlan(planId, revisionId);
        enqueueProcessing(revisionId);

        return new UploadResult(materialId, revisionId,
                (String) material.get("mime"), ((Number) material.get("size_bytes")).longValue());
    }

    private UUID createRevision(UUID materialId, String contentHash, int revisionNo) {
        UUID revisionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO material_revision (id, material_id, revision_no, source_content_hash, "
                        + " extraction_version, status) VALUES (?,?,?,?, 'pending', 'UPLOADED')",
                revisionId, materialId, revisionNo, contentHash);
        return revisionId;
    }

    private void linkToPlan(UUID planId, UUID revisionId) {
        if (planId != null) {
            jdbc.update("UPDATE study_plan SET material_revision_id = ?, status = 'ANALYZING' "
                    + " WHERE id = ?", revisionId, planId);
        }
    }

    private void enqueueProcessing(UUID revisionId) {
        jobs.enqueue(JOB_TYPE, "material:" + revisionId,
                Json.write(Map.of("materialRevisionId", revisionId.toString())), 2);
    }

    @Transactional(readOnly = true)
    public MaterialView find(UUID userId, UUID materialId) {
        Map<String, Object> material = requireOwned(userId, materialId);
        List<Map<String, Object>> revisions = jdbc.queryForList(
                "SELECT id, revision_no, status, quality_flags, failure_reason, created_at "
                        + "  FROM material_revision WHERE material_id = ? ORDER BY revision_no DESC",
                materialId);
        // How many plans still point at this file. The materials screen uses it to
        // say whether deleting or replacing the file would pull the rug out from
        // under a schedule someone is part-way through.
        Integer plansUsing = jdbc.queryForObject(
                "SELECT count(DISTINCT p.id) FROM study_plan p "
                        + "  JOIN material_revision r ON r.id = p.material_revision_id "
                        + " WHERE r.material_id = ? AND p.status <> 'ABANDONED'",
                Integer.class, materialId);

        return new MaterialView(
                materialId,
                (String) material.get("file_name"),
                (String) material.get("mime"),
                ((Number) material.get("size_bytes")).longValue(),
                (Integer) material.get("page_count"),
                String.valueOf(material.get("created_at")),
                plansUsing == null ? 0 : plansUsing,
                revisions);
    }

    @Transactional(readOnly = true)
    public List<MaterialView> listForUser(UUID userId) {
        return jdbc.queryForList(
                        "SELECT id FROM material WHERE user_id = ? ORDER BY created_at DESC", userId)
                .stream()
                .map(row -> find(userId, (UUID) row.get("id")))
                .toList();
    }

    /**
     * Short-lived signed URL for the in-app viewer. Ownership is checked here, in
     * the service layer, so every caller goes through the same gate.
     */
    @Transactional(readOnly = true)
    public String viewerUrl(UUID userId, UUID materialId) {
        Map<String, Object> material = requireOwned(userId, materialId);
        String viewerKey = (String) material.get("viewer_key");
        if (viewerKey == null) {
            throw new MaterialException("VIEWER_UNAVAILABLE",
                    "this material has no viewable rendition yet");
        }
        return storage.signedReadUrl(viewerKey,
                Duration.ofSeconds(storageProps.getSignedUrlTtlSeconds()));
    }

    @Transactional(readOnly = true)
    public InputStream openOriginal(UUID userId, UUID materialId) {
        Map<String, Object> material = requireOwned(userId, materialId);
        return storage.get((String) material.get("storage_key"));
    }

    /** Loads the bytes for a revision so the worker can parse them. */
    public InputStream openForRevision(UUID revisionId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT m.storage_key FROM material_revision r "
                        + "  JOIN material m ON m.id = r.material_id WHERE r.id = ?", revisionId);
        return storage.get((String) row.get("storage_key"));
    }

    public RevisionContext revisionContext(UUID revisionId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT r.id, r.material_id, m.file_name, m.mime, m.user_id "
                        + "  FROM material_revision r JOIN material m ON m.id = r.material_id "
                        + " WHERE r.id = ?", revisionId);
        return new RevisionContext(
                (UUID) row.get("id"), (UUID) row.get("material_id"),
                (UUID) row.get("user_id"), (String) row.get("file_name"), (String) row.get("mime"));
    }

    private Map<String, Object> requireOwned(UUID userId, UUID materialId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM material WHERE id = ? AND user_id = ?", materialId, userId);
        if (rows.isEmpty()) {
            // Same answer whether it does not exist or belongs to someone else.
            throw new MaterialException("MATERIAL_NOT_FOUND", "material not found");
        }
        return rows.get(0);
    }

    // ------------------------------------------------------------- helpers

    static boolean isSupported(String mime) {
        return isPdf(mime) || isPptx(mime);
    }

    static boolean isPdf(String mime) {
        return "application/pdf".equals(mime);
    }

    static boolean isPptx(String mime) {
        return "application/vnd.openxmlformats-officedocument.presentationml.presentation".equals(mime);
    }

    /** Magic-byte sniffing. PPTX is a ZIP, so the extension disambiguates OOXML. */
    static String sniffMime(byte[] content, String fileName, String declaredMime) {
        if (content.length >= 4
                && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F') {
            return "application/pdf";
        }
        boolean zip = content.length >= 4 && content[0] == 'P' && content[1] == 'K'
                && (content[2] == 3 || content[2] == 5 || content[2] == 7);
        if (zip && looksLikePptx(fileName, declaredMime)) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        // Nothing recognised. Falling back to the declared type here would undo
        // the whole point of sniffing: any bytes at all could be uploaded by
        // simply claiming they are a PDF. An unrecognised file is unrecognised.
        return "application/octet-stream";
    }

    /**
     * The name and the declared type may disambiguate a ZIP, but never admit one:
     * the container has to actually be a ZIP first.
     */
    private static boolean looksLikePptx(String fileName, String declaredMime) {
        return (fileName != null && fileName.toLowerCase().endsWith(".pptx"))
                || "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                        .equals(declaredMime);
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static byte[] readAll(InputStream input) {
        try (input) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new MaterialException("READ_FAILED", "cannot read uploaded file");
        }
    }

    public record UploadResult(UUID materialId, UUID materialRevisionId, String mime, long sizeBytes) {
    }

    public record MaterialView(UUID id, String fileName, String mime, long sizeBytes,
                               Integer pageCount, String uploadedAt, int plansUsing,
                               List<Map<String, Object>> revisions) {
    }

    public record RevisionContext(UUID revisionId, UUID materialId, UUID userId,
                                  String fileName, String mime) {
    }

    public static class MaterialException extends RuntimeException {
        private final String code;

        public MaterialException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
