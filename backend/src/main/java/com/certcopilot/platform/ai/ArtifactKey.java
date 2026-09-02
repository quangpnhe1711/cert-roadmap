package com.certcopilot.platform.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Correction A2: the effective cache identity of a generated artifact.
 *
 * <p>Reuse is only safe when <em>both</em> halves match:
 *
 * <ul>
 *   <li><b>Semantic inputs</b> - what the artifact is about: material revision,
 *       structure hash, learning unit content hash, certification version,
 *       mapping version.
 *   <li><b>Generation version</b> - how it was produced: operation id, prompt
 *       version, output schema version, model id, model config version.
 * </ul>
 *
 * <p>Anything that changes output semantics produces a different key, and
 * therefore a new artifact rather than a stale reuse. The two halves are hashed
 * separately as well so we can tell <em>why</em> a key changed when debugging a
 * regression.
 */
public final class ArtifactKey {

    private final String artifactType;
    private final Map<String, String> semanticInputs;
    private final Map<String, String> generationVersion;

    private ArtifactKey(String artifactType,
                        Map<String, String> semanticInputs,
                        Map<String, String> generationVersion) {
        this.artifactType = artifactType;
        this.semanticInputs = semanticInputs;
        this.generationVersion = generationVersion;
    }

    public static Builder of(String artifactType) {
        return new Builder(artifactType);
    }

    public String semanticInputHash() {
        return sha256(canonical(semanticInputs));
    }

    public String generationVersionHash() {
        return sha256(canonical(generationVersion));
    }

    /** The full key. This is what the unique index in {@code generated_artifact} holds. */
    public String value() {
        return sha256(artifactType + "" + semanticInputHash() + "" + generationVersionHash());
    }

    public String artifactType() {
        return artifactType;
    }

    /**
     * This key plus one more generation-version input.
     *
     * <p>Exists so the gateway can fold in which model would answer without
     * every caller having to know - callers describe what the artifact is about,
     * the gateway knows what would produce it.
     */
    public ArtifactKey withGeneration(String name, String value) {
        Map<String, String> extended = new LinkedHashMap<>(generationVersion);
        extended.put(Objects.requireNonNull(name), value);
        return new ArtifactKey(artifactType, semanticInputs, Map.copyOf(extended));
    }

    @Override
    public String toString() {
        return artifactType + ":" + value().substring(0, 16);
    }

    private static String canonical(Map<String, String> parts) {
        List<String> entries = new ArrayList<>(parts.size());
        parts.forEach((k, v) -> entries.add(k + "=" + (v == null ? "" : v)));
        entries.sort(String::compareTo);
        return String.join("", entries);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Builder that refuses to produce a key with an empty generation version.
     * A key missing its generation half would silently serve content produced by
     * an older prompt, which is the exact failure A2 exists to prevent.
     */
    public static final class Builder {
        private final String artifactType;
        private final Map<String, String> semantic = new LinkedHashMap<>();
        private final Map<String, String> generation = new LinkedHashMap<>();

        private Builder(String artifactType) {
            this.artifactType = Objects.requireNonNull(artifactType, "artifactType");
        }

        /** A semantic input: what the artifact is about. */
        public Builder semantic(String name, String value) {
            semantic.put(Objects.requireNonNull(name), value);
            return this;
        }

        /** A generation-version input: how the artifact was produced. */
        public Builder generation(String name, String value) {
            generation.put(Objects.requireNonNull(name), value);
            return this;
        }

        public Builder operation(AiOperation operation) {
            return generation("operationId", operation.id())
                    .generation("promptVersion", operation.promptVersion())
                    .generation("outputSchemaVersion", operation.outputSchemaVersion())
                    .generation("modelConfigVersion", operation.modelConfigVersion())
                    .generation("modelTier", operation.tier().name());
        }

        public ArtifactKey build() {
            if (semantic.isEmpty()) {
                throw new IllegalStateException("artifact key needs at least one semantic input");
            }
            if (generation.isEmpty()) {
                throw new IllegalStateException("artifact key needs a generation version");
            }
            return new ArtifactKey(artifactType,
                    Map.copyOf(semantic), Map.copyOf(generation));
        }
    }
}
