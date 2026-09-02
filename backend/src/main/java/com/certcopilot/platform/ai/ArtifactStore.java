package com.certcopilot.platform.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Correction A2: stores generated artifacts under their full identity key.
 *
 * <p>Nothing is ever overwritten. Publishing a new version of an artifact marks
 * the previous one {@code SUPERSEDED} and keeps it, because it is still the
 * content a learner actually studied.
 */
@Component
public class ArtifactStore {

    private final JdbcTemplate jdbc;

    public ArtifactStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Looks up a reusable artifact. Only {@code VALID} rows are served. */
    public Optional<Stored> findValid(ArtifactKey key) {
        List<Stored> rows = jdbc.query(
                "SELECT id, artifact_key, payload, cost_cents FROM generated_artifact "
                        + " WHERE artifact_key = ? AND cache_status = 'VALID'",
                STORED_MAPPER, key.value());
        return rows.stream().findFirst();
    }

    public UUID store(ArtifactKey key,
                      UUID planId,
                      String payloadJson,
                      AiOperation operation,
                      String model,
                      int tokensIn,
                      int tokensOut,
                      int costCents) {
        UUID id = UUID.randomUUID();

        // Any previous VALID row for this key steps aside rather than colliding.
        // Without this, invalidating a bad generation is a trap: reuse is blocked
        // and the replacement then fails on the unique key, so content a learner
        // reported as wrong can never actually be replaced.
        jdbc.update(
                "UPDATE generated_artifact SET cache_status = 'SUPERSEDED', superseded_by = ? "
                        + " WHERE artifact_key = ? AND cache_status = 'VALID'",
                id, key.value());

        jdbc.update(
                "INSERT INTO generated_artifact "
                        + "(id, artifact_key, artifact_type, plan_id, semantic_input_hash, "
                        + " generation_version_hash, cache_status, payload, operation_id, "
                        + " prompt_version, output_schema_version, model, model_config_version, "
                        + " tokens_in, tokens_out, cost_cents) "
                        + "VALUES (?,?,?,?,?,?,'VALID',?::jsonb,?,?,?,?,?,?,?,?)",
                id, key.value(), key.artifactType(), planId,
                key.semanticInputHash(), key.generationVersionHash(),
                payloadJson, operation.id(), operation.promptVersion(),
                operation.outputSchemaVersion(), model, operation.modelConfigVersion(),
                tokensIn, tokensOut, costCents);
        return id;
    }

    /**
     * Blocks reuse of an artifact a user reported as wrong.
     *
     * <p>This is the mitigation for risk AR-8: without it, the cache can turn one
     * bad generation into a permanent one.
     */
    public int invalidate(String artifactKey, String reason) {
        return jdbc.update(
                "UPDATE generated_artifact "
                        + "   SET cache_status = 'INVALIDATED', invalidated_reason = ? "
                        + " WHERE artifact_key = ? AND cache_status = 'VALID'",
                reason, artifactKey);
    }

    /** Marks a previous artifact superseded by a newer one, retaining both. */
    public int supersede(String oldArtifactKey, UUID newArtifactId) {
        return jdbc.update(
                "UPDATE generated_artifact "
                        + "   SET cache_status = 'SUPERSEDED', superseded_by = ? "
                        + " WHERE artifact_key = ? AND cache_status = 'VALID'",
                newArtifactId, oldArtifactKey);
    }

    public Optional<Stored> findById(UUID id) {
        List<Stored> rows = jdbc.query(
                "SELECT id, artifact_key, payload, cost_cents FROM generated_artifact WHERE id = ?",
                STORED_MAPPER, id);
        return rows.stream().findFirst();
    }

    private static final org.springframework.jdbc.core.RowMapper<Stored> STORED_MAPPER =
            (rs, n) -> new Stored(
                    rs.getObject("id", UUID.class),
                    rs.getString("artifact_key"),
                    rs.getString("payload"),
                    rs.getInt("cost_cents"));

    public record Stored(UUID id, String artifactKey, String payload, int costCents) {
    }
}
