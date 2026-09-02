package com.certcopilot.platform.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads versioned prompt templates from {@code resources/prompts}.
 *
 * <p>Prompts live in files rather than in Java strings for one practical reason:
 * getting the Vietnamese lesson quality right takes many iterations, and if each
 * one needed a redeploy the timeline would go first. In development the file is
 * re-read on every call, so editing a prompt and refreshing is the whole loop.
 *
 * <p>{@code promptVersion} is part of the artifact key, so changing a prompt
 * invalidates exactly the cached artifacts it should and no others.
 */
@Component
public class PromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptRegistry.class);

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final boolean hotReload;
    private final Path devSourceDir;

    public PromptRegistry(AiProperties props) {
        this.hotReload = props.isPromptHotReload();
        this.devSourceDir = Path.of("src/main/resources/prompts");
        if (hotReload) {
            log.info("prompt hot reload enabled from {}", devSourceDir.toAbsolutePath());
        }
    }

    public String load(String operationId, String version) {
        String name = operationId + "." + version + ".md";
        if (hotReload) {
            Path onDisk = devSourceDir.resolve(name);
            if (Files.exists(onDisk)) {
                try {
                    return Files.readString(onDisk, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("cannot hot-reload prompt {}: {}", name, e.toString());
                }
            }
        }
        return cache.computeIfAbsent(name, PromptRegistry::readClasspath);
    }

    /** Fills {@code {{placeholders}}}. Missing keys become empty rather than throwing. */
    public String render(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return result.replaceAll("\\{\\{[a-zA-Z0-9_]+}}", "");
    }

    private static String readClasspath(String name) {
        try (var in = new ClassPathResource("prompts/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("missing prompt template: prompts/" + name, e);
        }
    }
}
