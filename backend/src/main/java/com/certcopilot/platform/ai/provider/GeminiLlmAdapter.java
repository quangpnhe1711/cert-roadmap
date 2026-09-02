package com.certcopilot.platform.ai.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
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
 * Production adapter for the Google Gemini API ({@code generateContent}).
 *
 * <p>One of three implementations of {@link LlmPort}, selected by
 * {@code app.ai.adapter=gemini}. Like the Anthropic adapter it is reached
 * exclusively through {@code AiGateway} - architecture rule R5 fails the build if
 * anything else calls the port - so caching, budget reservation and the cost
 * ledger cannot be bypassed by adding a second call site.
 *
 * <p>Built on {@code java.net.http} rather than the Google SDK, for the same
 * reason the Anthropic adapter is: the request is one JSON POST, and an SDK would
 * add a dependency, a transitive Jackson version to reconcile, and its own retry
 * policy competing with this one.
 *
 * <h2>Structured output</h2>
 *
 * <p>Every operation must return one JSON object, and Gemini can be told so
 * directly: {@code responseMimeType: application/json} constrains decoding rather
 * than merely asking politely, which is stronger than the assistant-prefill trick
 * the Anthropic adapter needs. No response schema is sent, because the six
 * operations have six different shapes already described precisely in their
 * prompt files, and a second copy in code would be one more thing to keep in
 * step. The reply is still normalised defensively, so a fenced or truncated
 * answer fails as a parse error the gateway can see and has priced.
 *
 * <h2>Thinking</h2>
 *
 * <p>Gemini bills thinking tokens as output tokens and counts them against
 * {@code maxOutputTokens}. Left alone, a model can spend an operation's entire
 * output budget reasoning and return {@code MAX_TOKENS} with an empty answer -
 * paid for, and worthless. {@link GeminiProperties#thinkingFor} makes the budget
 * explicit per tier, and a token budget is added on top of the operation's own
 * ceiling rather than taken out of it.
 *
 * <h2>What is not logged</h2>
 *
 * <p>Never the prompt, never the output, never the API key, and never a provider
 * error message unless explicitly enabled. The prompt contains the learner's own
 * course material. Sizes, token counts, model, status, finish reason and error
 * status are logged, which is what diagnosis actually needs.
 */
@Component
@ConditionalOnProperty(name = "app.ai.adapter", havingValue = "gemini")
public class GeminiLlmAdapter implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmAdapter.class);

    private static final String SYSTEM_PROMPT = """
            Bạn trả lời bằng đúng một JSON object hợp lệ, không kèm giải thích, \
            không kèm markdown, không kèm code fence.
            You reply with exactly one valid JSON object. No prose, no markdown, no code fences.""";

    /**
     * Finish reasons that mean the model refused rather than answered. Retrying
     * the same material would be refused the same way, so these are permanent;
     * the gateway reports them instead of spending two more reservations.
     */
    private static final Set<String> BLOCKED_FINISH_REASONS = Set.of(
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY");

    private final GeminiProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public GeminiLlmAdapter(GeminiProperties props, ObjectMapper mapper) {
        if (!props.hasApiKey()) {
            // Fail at startup, not at the first learner's first lesson.
            throw new IllegalStateException(
                    "app.ai.adapter=gemini but no API key is configured. "
                            + "Set GEMINI_API_KEY, or app.ai.adapter=fake for development and CI.");
        }
        // Parsed once so a malformed thinking setting fails at boot rather than
        // halfway through a learner's first plan.
        for (com.certcopilot.platform.ai.ModelTier tier : com.certcopilot.platform.ai.ModelTier.values()) {
            props.modelFor(tier);
            props.thinkingFor(tier);
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
        GeminiProperties.Thinking thinking = props.thinkingFor(request.tier());
        String body = buildRequestBody(request, thinking);

        long start = System.nanoTime();
        HttpResponse<String> response = sendWithTransportRetries(body, request, model);
        long latencyMs = Math.max(1, (System.nanoTime() - start) / 1_000_000);

        return readResponse(response.body(), request, model, latencyMs);
    }

    // ------------------------------------------------------------- request

    private String buildRequestBody(Request request, GeminiProperties.Thinking thinking) {
        ObjectNode root = mapper.createObjectNode();

        ObjectNode system = root.putObject("systemInstruction");
        system.putArray("parts").addObject().put("text", SYSTEM_PROMPT);

        ArrayNode contents = root.putArray("contents");
        ObjectNode user = contents.addObject();
        user.put("role", "user");
        user.putArray("parts").addObject().put("text", userContent(request));

        ObjectNode config = root.putObject("generationConfig");
        config.put("temperature", props.getTemperature());
        // The thinking budget is added on top: the operation asked for room for an
        // answer, not room for an answer minus however much the model reasoned.
        config.put("maxOutputTokens",
                Math.max(1, request.maxOutputTokens()) + thinking.extraOutputTokens());
        config.put("responseMimeType", "application/json");

        switch (thinking.mode()) {
            case LEVEL -> config.putObject("thinkingConfig").put("thinkingLevel", thinking.level());
            case BUDGET -> config.putObject("thinkingConfig").put("thinkingBudget", thinking.budget());
            case DEFAULT -> { /* take the model's own default */ }
        }

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

    private HttpResponse<String> sendWithTransportRetries(String body, Request request, String model) {
        int attempts = Math.max(1, props.getMaxTransportAttempts());
        long backoffMs = props.getInitialBackoffMs();
        LlmException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw LlmException.cancelled("cancelled before provider call");
            }
            try {
                HttpResponse<String> response = http.send(
                        httpRequest(body, model), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() / 100 == 2) {
                    return response;
                }

                LlmException mapped = mapHttpError(response, model);
                logProviderFailure(request, attempt, attempts, mapped);
                if (!mapped.retryable()) {
                    throw mapped;
                }
                last = mapped;
                // A provider-directed wait is honoured as given and does not
                // become the next backoff: it describes this quota window, not
                // how long the next failure would be worth waiting for.
                long directed = providerDirectedWaitMs(response);
                if (directed > 0) {
                    pauseIfMoreAttempts(attempt, attempts, directed);
                } else {
                    backoffMs = pauseIfMoreAttempts(attempt, attempts, backoffMs);
                }

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

    /**
     * The key travels in a header, not in the {@code ?key=} query parameter the
     * quickstarts use. A URL ends up in access logs, proxy logs and exception
     * messages; a header does not.
     */
    private HttpRequest httpRequest(String body, String model) {
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint(model)))
                .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                .header("content-type", "application/json")
                .header("x-goog-api-key", props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private String endpoint(String model) {
        return props.getBaseUrl() + "/" + props.getApiVersion()
                + "/models/" + model + ":generateContent";
    }

    /**
     * Retry classification. Anything that describes the request itself - a bad
     * key, a model this account cannot use, a malformed body - cannot improve by
     * being sent again.
     */
    private LlmException mapHttpError(HttpResponse<String> response, String model) {
        int status = response.statusCode();
        String type = errorStatus(response.body());
        String message = "provider returned HTTP " + status
                + (type == null ? "" : " (" + type + ")")
                + (status == 404 ? " for model " + model : "")
                + (props.isLogProviderMessages() ? ": " + safe(errorMessage(response.body())) : "");

        boolean retryable = status == 408
                || status == 429
                || status >= 500;

        return retryable
                ? LlmException.retryable(status, type, message)
                : LlmException.permanent(status, type, message);
    }

    /**
     * How long the provider itself asked us to wait, or -1 if it did not say.
     *
     * <p>Gemini answers a quota failure with a {@code RetryInfo} detail rather
     * than a {@code Retry-After} header, so both are consulted. The result is
     * bounded by {@code maxRetryAfterMs} rather than by {@code maxBackoffMs}: a
     * rate limit that reopens in 48 seconds cannot be waited out in 8, and
     * clamping it to our own guess turns a recoverable pause into a reported
     * outage.
     */
    private long providerDirectedWaitMs(HttpResponse<String> response) {
        long fromHeader = response.headers().firstValue("retry-after")
                .map(value -> {
                    try {
                        return Long.parseLong(value.trim()) * 1000L;
                    } catch (NumberFormatException ignored) {
                        return -1L;
                    }
                })
                .orElse(-1L);
        long chosen = fromHeader > 0 ? fromHeader : retryDelayFromBody(response.body());
        // A little slack: retrying the instant the window reopens races it.
        return chosen > 0 ? Math.min(chosen + 500, props.getMaxRetryAfterMs()) : -1;
    }

    /** {@code error.details[].retryDelay} arrives as a duration string such as "27s". */
    private long retryDelayFromBody(String body) {
        try {
            for (JsonNode detail : mapper.readTree(body).path("error").path("details")) {
                String delay = detail.path("retryDelay").asText("");
                if (!delay.isBlank() && delay.endsWith("s")) {
                    return (long) (Double.parseDouble(delay.substring(0, delay.length() - 1)) * 1000);
                }
            }
        } catch (Exception ignored) {
            // A provider error body that is not the documented shape is not worth failing over.
        }
        return -1;
    }

    /** No point waiting after the final attempt; the caller is about to give up. */
    private long pauseIfMoreAttempts(int attempt, int attempts, long backoffMs) {
        return attempt < attempts ? sleepBeforeRetry(backoffMs) : backoffMs;
    }

    /** Sleeps with jitter and returns the next backoff. Interrupt means cancel, not swallow. */
    private long sleepBeforeRetry(long waitMs) {
        long jittered = waitMs + ThreadLocalRandom.current().nextLong(1 + waitMs / 5);
        try {
            Thread.sleep(Math.min(jittered, props.getMaxRetryAfterMs()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LlmException.cancelled("interrupted while backing off");
        }
        return Math.min(waitMs * 2, props.getMaxBackoffMs());
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

        String model = root.path("modelVersion").asText(requestedModel);
        JsonNode usage = root.path("usageMetadata");
        // Cached input is still input as far as the ledger is concerned, and
        // thinking is billed as output. Leaving either out would under-report
        // exactly the calls we are trying to make cheaper.
        int tokensIn = usage.path("promptTokenCount").asInt(0)
                + usage.path("cachedContentTokenCount").asInt(0);
        int tokensOut = usage.path("candidatesTokenCount").asInt(0)
                + usage.path("thoughtsTokenCount").asInt(0);

        // A prompt refused before generation has no candidate at all. It is a
        // 200, so it must be turned into a failure here or it would surface as an
        // empty lesson.
        String promptBlock = root.path("promptFeedback").path("blockReason").asText("");
        if (!promptBlock.isBlank()) {
            log.warn("ai.provider prompt blocked op={} model={} reason={}",
                    request.operationId(), model, promptBlock);
            throw LlmException.permanent(200, "prompt_blocked",
                    "provider refused the request before generating (blockReason=" + promptBlock + ")");
        }

        JsonNode candidate = root.path("candidates").path(0);
        String finishReason = candidate.path("finishReason").asText("");
        if (BLOCKED_FINISH_REASONS.contains(finishReason)) {
            log.warn("ai.provider response blocked op={} model={} finish={} tokensIn={} tokensOut={}",
                    request.operationId(), model, finishReason, tokensIn, tokensOut);
            throw LlmException.permanent(200, "content_blocked",
                    "provider stopped generating (finishReason=" + finishReason + ")");
        }

        String text = concatenateText(candidate.path("content").path("parts"));

        if ("MAX_TOKENS".equals(finishReason)) {
            // Returned anyway. The tokens were spent, so the ledger must see them;
            // the gateway records the parse failure that follows. Repairing the
            // JSON here would invent content the model never produced.
            log.warn("ai.provider truncated op={} model={} maxTokens={} tokensOut={} thinking={}",
                    request.operationId(), model, request.maxOutputTokens(), tokensOut,
                    usage.path("thoughtsTokenCount").asInt(0));
        }

        log.info("ai.provider op={} model={} attempt={} tokensIn={} tokensOut={} latencyMs={} "
                        + "finish={} outChars={}",
                request.operationId(), model, request.attemptNo(),
                tokensIn, tokensOut, latencyMs, finishReason, text.length());

        return new Response(normaliseJson(text), model, tokensIn, tokensOut, latencyMs);
    }

    /**
     * Joins the answer parts and skips the thought parts.
     *
     * <p>Thought summaries arrive as parts flagged {@code thought}; concatenating
     * them into the payload would hand the validator prose where it expects JSON.
     */
    private String concatenateText(JsonNode parts) {
        if (!parts.isArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.path("thought").asBoolean(false)) {
                continue;
            }
            out.append(part.path("text").asText(""));
        }
        return out.toString();
    }

    /**
     * Strips anything a model might wrap the object in. Not a repair: truncated
     * JSON stays truncated and fails as a parse error, which is the honest
     * outcome and one the ledger has priced.
     */
    static String normaliseJson(String raw) {
        String trimmed = raw == null ? "" : raw.strip();

        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            trimmed = firstNewline < 0 ? trimmed.substring(3) : trimmed.substring(firstNewline + 1);
            int fenceEnd = trimmed.lastIndexOf("```");
            if (fenceEnd >= 0) {
                trimmed = trimmed.substring(0, fenceEnd);
            }
            trimmed = trimmed.strip();
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    // ------------------------------------------------------------- logging

    private void logProviderFailure(Request request, int attempt, int attempts, LlmException e) {
        log.warn("ai.provider failure op={} attempt={}/{} status={} type={} kind={}",
                request.operationId(), attempt, attempts,
                e.httpStatus(), e.providerErrorType(), e.kind());
    }

    /** Gemini's canonical error code, e.g. {@code RESOURCE_EXHAUSTED} or {@code PERMISSION_DENIED}. */
    private String errorStatus(String body) {
        try {
            JsonNode error = mapper.readTree(body).path("error");
            String status = error.path("status").asText("");
            return status.isBlank() ? null : status;
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
        return "gemini:" + props.modelFor(tier);
    }
}
