package com.certcopilot.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.api.security.CurrentUser;
import com.certcopilot.domain.catalog.CatalogService;
import com.certcopilot.domain.learning.LearningPackService;
import com.certcopilot.domain.material.MaterialService;
import com.certcopilot.domain.planning.PlanService;
import com.certcopilot.platform.ai.AiModeService;
import com.certcopilot.platform.storage.LocalFileSystemStorage;
import com.certcopilot.platform.storage.ObjectStoragePort;
import com.certcopilot.platform.storage.StorageProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Catalog, material upload and viewing, and the daily learning pack.
 *
 * <p>The original-material viewer is deliberately here rather than deferred: the
 * lesson is positioned as a companion to the learner's own slides, and a
 * companion that cannot show you the slide is just an assertion.
 */
@RestController
@RequestMapping("/api/v1")
public class LearningController {

    private final CatalogService catalog;
    private final MaterialService materials;
    private final LearningPackService packs;
    private final PlanService plans;
    private final AiModeService aiMode;
    private final ObjectStoragePort storage;
    private final StorageProperties storageProps;

    public LearningController(CatalogService catalog, MaterialService materials,
                              LearningPackService packs, PlanService plans,
                              AiModeService aiMode,
                              ObjectStoragePort storage, StorageProperties storageProps) {
        this.catalog = catalog;
        this.materials = materials;
        this.packs = packs;
        this.plans = plans;
        this.aiMode = aiMode;
        this.storage = storage;
        this.storageProps = storageProps;
    }

    // ---------------------------------------------------------------- catalog

    /**
     * What the caller needs to know about this deployment before trusting what
     * it shows them.
     *
     * <p>Exists so the client can label synthetic output. A learner who cannot
     * tell fixture prose from a real lesson has no way to calibrate how much to
     * believe, and neither has anyone being shown a demo.
     */
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        AiModeService.Descriptor ai = aiMode.describe();
        return Map.of(
                "aiMode", ai.mode(),
                "aiModeLabel", ai.synthetic() ? "Development / Synthetic AI" : ai.label(),
                "aiProvider", ai.provider(),
                "aiProviderLabel", ai.label(),
                "aiConfigured", ai.configured(),
                "aiSynthetic", ai.synthetic(),
                "fastModel", ai.fastModel(),
                "qualityModel", ai.qualityModel());
    }

    @GetMapping("/certifications")
    public List<CatalogService.CertificationSummary> certifications(
            @RequestParam(required = false) String q) {
        return catalog.search(q);
    }

    @GetMapping("/certifications/{versionId}/blueprint")
    public CatalogService.Blueprint blueprint(@PathVariable UUID versionId) {
        return catalog.blueprint(versionId);
    }

    // --------------------------------------------------------------- material

    @PostMapping(path = "/plans/{planId}/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MaterialService.UploadResult> upload(@PathVariable UUID planId,
                                                               @RequestPart("file") MultipartFile file)
            throws IOException {
        MaterialService.UploadResult result = materials.upload(
                CurrentUser.id(), planId,
                file.getOriginalFilename(), file.getContentType(), file.getBytes());
        // Processing continues asynchronously; the client polls /analysis.
        return ResponseEntity.accepted().body(result);
    }

    @GetMapping("/materials")
    public List<MaterialService.MaterialView> materials() {
        return materials.listForUser(CurrentUser.id());
    }

    @GetMapping("/materials/{materialId}")
    public MaterialService.MaterialView material(@PathVariable UUID materialId) {
        return materials.find(CurrentUser.id(), materialId);
    }

    /** Short-lived signed URL for the viewer. Ownership is checked in the service. */
    @GetMapping("/materials/{materialId}/viewer-url")
    public Map<String, Object> viewerUrl(@PathVariable UUID materialId) {
        return Map.of(
                "url", materials.viewerUrl(CurrentUser.id(), materialId),
                "expiresInSeconds", storageProps.getSignedUrlTtlSeconds());
    }

    @PostMapping("/materials/{materialId}/reprocess")
    public ResponseEntity<MaterialService.UploadResult> reprocess(@PathVariable UUID materialId,
                                                                  @RequestParam UUID planId) {
        return ResponseEntity.accepted()
                .body(materials.reprocess(CurrentUser.id(), planId, materialId));
    }

    /**
     * Serves a stored file against an HMAC-signed URL.
     *
     * <p>Unauthenticated by design - the signature and its expiry are the
     * credential - which is what lets pdf.js load the document directly.
     */
    @GetMapping("/files/**")
    public ResponseEntity<InputStreamResource> file(jakarta.servlet.http.HttpServletRequest request,
                                                    @RequestParam long expires,
                                                    @RequestParam String sig) {
        String key = extractKey(request.getRequestURI());
        if (!(storage instanceof LocalFileSystemStorage local)
                || !local.verifySignature(key, expires, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!storage.exists(key)) {
            return ResponseEntity.notFound().build();
        }
        InputStream stream = storage.get(key);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600")
                .body(new InputStreamResource(stream));
    }

    private static String extractKey(String uri) {
        String prefix = "/api/v1/files/";
        String raw = uri.substring(uri.indexOf(prefix) + prefix.length());
        return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------- learning pack

    /**
     * Returns the lesson for a unit, generating it if the worker has not yet.
     *
     * <p>202 means "being prepared, poll again" rather than an error: the normal
     * path is that pre-generation already ran and this returns immediately.
     */
    @GetMapping("/plans/{planId}/units/{unitId}/pack")
    public ResponseEntity<?> pack(@PathVariable UUID planId, @PathVariable UUID unitId) {
        // A pack is written from the owner's own uploaded material and cites its
        // pages. Serving one without this check hands over their course content,
        // and generating one spends their AI budget to do it.
        plans.requireOwned(CurrentUser.id(), planId);
        return packs.findExisting(planId, unitId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> packs.generate(planId, unitId)
                        .<ResponseEntity<?>>map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.accepted().body(Map.of(
                                "status", "GENERATING",
                                "message", "Bài giảng đang được chuẩn bị. Vui lòng thử lại sau ít giây."))));
    }

    @PostMapping("/content-flags")
    public ResponseEntity<Void> flag(@RequestBody @Valid FlagRequest request) {
        packs.flagBlock(CurrentUser.id(), request.blockId(), request.reason(), request.note());
        return ResponseEntity.noContent().build();
    }

    public record FlagRequest(@NotNull UUID blockId, @NotBlank String reason, String note) {
    }
}
