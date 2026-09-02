package com.certcopilot.platform.ai.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import com.certcopilot.platform.ai.LlmException;
import com.certcopilot.platform.ai.LlmPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production adapter for the Anthropic Messages API.
 *
 * <p>The other implementation of {@link LlmPort} is the deterministic fake used
 * by development and CI. This one is the only code in the product that opens a
 * socket to a model, and it is reached exclusively through {@code AiGateway} -
 * architecture rule R5 fails the build if anything else calls the port, so
 * caching, budget reservation and the cost ledger cannot be bypassed by adding a
 * second call site.
 *
 * <p>Deliberately built on {@code java.net.http} rather than a provider SDK. The
 * request is one JSON POST; an SDK would add a dependency, a transitive Jackson
 * version to reconcile, and its own retry policy competing with this one.
 *
 * <h2>Structured output</h2>
 *
 * <p>Every operation must return one JSON object. That is forced by prefilling
 * the assistant turn with <code>{</code>: the model can only continue an object,
 * so there is no preamble to strip and no code fence to survive. The response is
 * still normalised defensively, because a truncated or fenced reply must fail as
 * a parse error the gateway can see rather than as an exception that hides the
 * tokens it cost.
 *
 * <h2>What is not logged</h2>
 *
 * <p>Never the prompt, never the output, never a provider error message unless
 * explicitly enabled. The prompt contains the learner's own course material.
 * Sizes, token counts, model, status and error type are logged, which is what
 * diagnosis actually needs.
 */
@Component
@ConditionalOnProperty(name = "app.ai.adapter", havingValue = "anthropic")
public class AnthropicLlmAdapter implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmAdapter.class);

    /** Forces a bare JSON object: the model has no room for a preamble. */
    private static final String PREFILL = "{";

    private static final String SYSTEM_PROMPT = """
            Bạn trả lời bằng đúng một JSON object hợp lệ, không kèm giải thích, \
            không kèm markdown, không kèm code fence.
            You reply with exactly one valid JSON object. No prose, no markdown, no code fences.""";

    private final ProviderProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public AnthropicLlmAdapter(ProviderProperties props, ObjectMapper mapper) {
        if (!props.hasApiKey()) {
            // Fail at startup, not at the first learner's first lesson.
            throw new IllegalStateException(
                    "app.ai.adapter=anthropic but no API key is configured. "
                            + "Set ANTHROPIC_API_KEY, or app.ai.adapter=fake for development and CI.");
        }
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                // An API key must never be replayed to a redirect target.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Response call(Request request) {
        String model = props.modelFor(request.tier());
        String body = buildRequestBody(request, model);

        long start = System.nanoTime();
        HttpResponse<String> response = sendWithTransportRetries(body, request);
        long latencyMs = Math.max(1, (System.nanoTime() - start) / 1_000_000);

        return readResponse(response.body(), request, model, latencyMs);
    }

    // ------------------------------------------------------------- request

    private String buildRequestBody(Request request, String model) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", Math.max(1, request.maxOutputTokens()));
        root.put("temperature", props.getTemperature());
        root.put("system", SYSTEM_PROMPT);

        ArrayNode messages = root.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userContent(request));

        // Prefill: the reply is a continuation of an already-open JSON object.
        ObjectNode assistant = messages.addObject();
        assistant.put("role", "assistant");
        assistant.put("content", PREFILL);

        return root.toString();
    }

    /**
     * On a retry the gateway passes why the previous output was rejected. Telling
     * the model what was wrong is the whole point of retrying: sending the same
     * prompt again just buys the same rejection at the same price.
     */
    private String userContent(Request request) {
        if (request.validationFeedback() == null || request.validationFeedback().isBlank()) {
            return request.prompt();
        }
        return request.prompt() + """


                ---
                Câu trả lời trước của bạn đã bị từ chối vì: %s
                Hãy sửa đúng lỗi đó. Chỉ trả về JSON hợp lệ theo schema ở trên.""".formatted(
                request.validationFeedback());
    }

    // ------------------------------------------------------------- transport

    private HttpResponse<String> sendWithTransportRetries(String body, Request request) {
        int attempts = Math.max(1, props.getMaxTransportAttempts());
        long backoffMs = props.getInitialBackoffMs();
        LlmException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw LlmException.cancelled("cancelled before provider call");
            }
            try {
                HttpResponse<String> response = http.send(
                        httpRequest(body), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() / 100 == 2) {
                    return response;
                }

                LlmException mapped = mapHttpError(response);
                logProviderFailure(request, attempt, attempts, mapped);
                if (!mapped.retryable()) {
                    throw mapped;
                }
                last = mapped;
                backoffMs = pauseIfMoreAttempts(attempt, attempts, retryAfterMs(response, backoffMs));

            } catch (HttpTimeoutException e) {
                last = LlmException.retryable(null, "timeout",
                        "provider did not respond within " + props.getRequestTimeoutMs() + "ms");
                logProviderFailure(request, attempt, attempts, last);
                backoffMs = pauseIfMoreAttempts(attempt, attempts, backoffMs);

            } catch (IOException e) {
                last = LlmException.retryable(null, "io", "transport failure: " + e.getClass().getSimpleName());
                logProviderFailure(request, attempt, attempts, last);
                backoffMs = pauseIfMoreAttempts(attempt, attempts, backoffMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw LlmException.cancelled("interrupted while waiting for the provider");
            }
        }

        // The adapter has already served the wait. Repeating it inside the same
        // job would only stall the worker; the job queue retries with its own backoff.
        throw last.asPermanent(
                "provider unavailable after " + attempts + " transport attempts: " + last.getMessage());
    }

    private HttpRequest httpRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + "/v1/messages"))
                .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                .header("content-type", "application/json")
                .header("x-api-key", props.getApiKey())
                .header("anthropic-version", props.getAnthropicVersion())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * Retry classification. Anything that describes the request itself - a bad
     * key, a malformed body, a payload over the limit - cannot improve by being
     * sent again.
     */
    private LlmException mapHttpError(HttpResponse<String> response) {
        int status = response.statusCode();
        String type = errorType(response.body());
        String message = "provider returned HTTP " + status
                + (type == null ? "" : " (" + type + ")")
                + (props.isLogProviderMessages() ? ": " + safe(errorMessage(response.body())) : "");

        boolean retryable = status == 408
                || status == 409
                || status == 429
                || status == 529
                || status >= 500;

        return retryable
                ? LlmException.retryable(status, type, message)
                : LlmException.permanent(status, type, message);
    }

    private long retryAfterMs(HttpResponse<String> response, long fallbackMs) {
        return response.headers().firstValue("retry-after")
                .map(value -> {
                    try {
                        return Math.min(Long.parseLong(value.trim()) * 1000L, props.getMaxBackoffMs());
                    } catch (NumberFormatException ignored) {
                        return fallbackMs;
                    }
                })
                .orElse(fallbackMs);
    }

    /** No point waiting after the final attempt; the caller is about to give up. */
    private long pauseIfMoreAttempts(int attempt, int attempts, long backoffMs) {
        return attempt < attempts ? sleepBeforeRetry(backoffMs) : backoffMs;
    }

    /** Sleeps with jitter and returns the next backoff. Interrupt means cancel, not swallow. */
    private long sleepBeforeRetry(long backoffMs) {
        long jittered = backoffMs + ThreadLocalRandom.current().nextLong(1 + backoffMs / 5);
        try {
            Thread.sleep(Math.min(jittered, props.getMaxBackoffMs()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LlmException.cancelled("interrupted while backing off");
        }
        return Math.min(backoffMs * 2, props.getMaxBackoffMs());
    }

    // ------------------------------------------------------------- response

    private Response readResponse(String rawBody, Request request, String requestedModel, long latencyMs) {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw LlmException.permanent(200, "unreadable_body",
                    "provider returned a 2xx that was not JSON");
        }

        String text = concatenateText(root.path("content"));
        String model = root.path("model").asText(requestedModel);
        String stopReason = root.path("stop_reason").asText("");

        JsonNode usage = root.path("usage");
        // Cached input is still input as far as the ledger is concerned; leaving it
        // out would under-report exactly the calls we are trying to make cheaper.
        int tokensIn = usage.path("input_tokens").asInt(0)
                + usage.path("cache_creation_input_tokens").asInt(0)
                + usage.path("cache_read_input_tokens").asInt(0);
        int tokensOut = usage.path("output_tokens").asInt(0);

        if ("max_tokens".equals(stopReason)) {
            // Return it anyway. The tokens were spent, so the ledger must see them;
            // the gateway will record the parse failure that follows.
            log.warn("ai.provider truncated op={} model={} maxTokens={} tokensOut={}",
                    request.operationId(), model, request.maxOutputTokens(), tokensOut);
        }

        log.info("ai.provider op={} model={} attempt={} tokensIn={} tokensOut={} latencyMs={} stop={} outChars={}",
                request.operationId(), model, request.attemptNo(),
                tokensIn, tokensOut, latencyMs, stopReason, text.length());

        return new Response(normaliseJson(text), model, tokensIn, tokensOut, latencyMs);
    }

    private String concatenateText(JsonNode content) {
        if (!content.isArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                out.append(block.path("text").asText(""));
            }
        }
        return out.toString();
    }

    /**
     * Reassembles the prefilled object and strips anything a model might wrap it
     * in. Not a repair: if the JSON is truncated it stays truncated and fails as
     * a parse error, which is the honest outcome and one the ledger has priced.
     */
    static String normaliseJson(String raw) {
        String trimmed = raw == null ? "" : raw.strip();

        // Normally a continuation of the prefill; occasionally a model restates
        // the whole object, in which case prepending "{" would corrupt it.
        String assembled = trimmed.startsWith("{") || trimmed.startsWith("```")
                ? trimmed
                : PREFILL + raw;

        String unfenced = assembled.strip();
        if (unfenced.startsWith("```")) {
            int firstNewline = unfenced.indexOf('\n');
            unfenced = firstNewline < 0 ? unfenced.substring(3) : unfenced.substring(firstNewline + 1);
            int fenceEnd = unfenced.lastIndexOf("```");
            if (fenceEnd >= 0) {
                unfenced = unfenced.substring(0, fenceEnd);
            }
            unfenced = unfenced.strip();
        }

        int start = unfenced.indexOf('{');
        int end = unfenced.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return unfenced.substring(start, end + 1);
        }
        return unfenced;
    }

    // ------------------------------------------------------------- logging

    private void logProviderFailure(Request request, int attempt, int attempts, LlmException e) {
        log.warn("ai.provider failure op={} attempt={}/{} status={} type={} kind={}",
                request.operationId(), attempt, attempts,
                e.httpStatus(), e.providerErrorType(), e.kind());
    }

    private String errorType(String body) {
        try {
            return mapper.readTree(body).path("error").path("type").asText(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String errorMessage(String body) {
        try {
            return mapper.readTree(body).path("error").path("message").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Single line, bounded length: a provider message is not a place for surprises. */
    private String safe(String message) {
        String flat = message == null ? "" : message.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    @Override
    public String generationIdentity(com.certcopilot.platform.ai.ModelTier tier) {
        return "anthropic:" + props.modelFor(tier);
    }
}
