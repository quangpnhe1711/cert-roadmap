package com.certcopilot.platform.ai.provider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.certcopilot.platform.ai.LlmException;
import com.certcopilot.platform.ai.LlmPort;
import com.certcopilot.platform.ai.ModelTier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for the Gemini adapter, run against a stub HTTP server.
 *
 * <p>Same job as {@link AnthropicLlmAdapterContractTest}: make "the Gemini
 * adapter is implemented" a checkable claim rather than an assertion, with no
 * credential and no network. It covers authentication, tier-to-model selection,
 * JSON mode, the thinking budget, token accounting including thoughts, error and
 * retry classification, safety blocks, truncation, timeouts, cancellation and
 * safe logging.
 *
 * <p>What it cannot cover is whether the real API behaves as documented - that is
 * what the opt-in live verification is for.
 */
class GeminiLlmAdapterContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Distinctive strings that must never reach a log line. */
    private static final String SECRET_PROMPT = "LEARNER-MATERIAL-SENTINEL-do-not-log";
    private static final String SECRET_OUTPUT = "GENERATED-OUTPUT-SENTINEL-do-not-log";
    private static final String SECRET_KEY = "API-KEY-SENTINEL-do-not-log";

    private StubGemini provider;
    private GeminiProperties props;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void startStub() throws IOException {
        provider = new StubGemini();
        props = new GeminiProperties();
        props.setBaseUrl(provider.baseUrl());
        props.setApiKey(SECRET_KEY);
        props.setInitialBackoffMs(1);
        props.setMaxBackoffMs(5);
        props.setMaxRetryAfterMs(50);

        logs = new ListAppender<>();
        logs.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        logs.start();
        logger().addAppender(logs);
        logger().setLevel(Level.DEBUG);
    }

    @AfterEach
    void stopStub() {
        logger().detachAppender(logs);
        logger().setLevel(null);
        provider.stop();
    }

    // ----------------------------------------------------------- credentials

    @Test
    @DisplayName("refuses to start without a credential rather than failing on the first lesson")
    void refusesToStartWithoutAnApiKey() {
        GeminiProperties keyless = new GeminiProperties();
        keyless.setApiKey("");

        assertThatThrownBy(() -> new GeminiLlmAdapter(keyless, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no API key is configured")
                .hasMessageContaining("app.ai.adapter=fake");
    }

    @Test
    @DisplayName("a thinking setting that cannot be honoured fails at boot, not mid-plan")
    void refusesToStartWithAnUnparseableThinkingSetting() {
        props.getThinking().put(ModelTier.FAST, "MEDIUM-ISH");

        assertThatThrownBy(this::adapter)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("thinking");
    }

    // ------------------------------------------------------- request mapping

    @Test
    @DisplayName("authenticates by header, selects the tier's model, and asks for JSON")
    void mapsTheRequest() throws Exception {
        provider.enqueueSuccess("{\"sections\":[]}", 1200, 340);
        props.getModels().put(ModelTier.QUALITY, "test-quality-model");
        props.getThinking().put(ModelTier.QUALITY, "NONE");

        adapter().call(request(ModelTier.QUALITY, "prompt text", 4096, 1, null));

        StubGemini.Recorded recorded = provider.only();
        // A key in the query string ends up in access logs, proxy logs and
        // exception messages. A header does not.
        assertThat(recorded.header("x-goog-api-key")).containsExactly(SECRET_KEY);
        assertThat(recorded.path())
                .isEqualTo("/v1beta/models/test-quality-model:generateContent");
        assertThat(recorded.query()).as("the key must not travel in the URL").isNullOrEmpty();

        JsonNode body = MAPPER.readTree(recorded.body());
        JsonNode config = body.path("generationConfig");
        assertThat(config.path("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(config.path("maxOutputTokens").asInt()).isEqualTo(4096);
        assertThat(config.path("temperature").asDouble()).isZero();
        assertThat(config.has("thinkingConfig")).isFalse();
        assertThat(body.path("contents").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("contents").get(0).path("parts").get(0).path("text").asText())
                .contains("prompt text");
        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText())
                .contains("JSON");
    }

    @Test
    @DisplayName("the fast tier uses the fast model, so tier is a real cost lever")
    void selectsTheFastModelForTheFastTier() {
        provider.enqueueSuccess("{\"topics\":[]}", 10, 10);
        props.getModels().put(ModelTier.FAST, "test-fast-model");

        adapter().call(request(ModelTier.FAST, "p", 512, 1, null));

        assertThat(provider.only().path()).contains("test-fast-model");
    }

    @Test
    @DisplayName("a thinking level is sent as thinkingLevel for models that take one")
    void sendsThinkingLevel() throws Exception {
        provider.enqueueSuccess("{\"a\":1}", 10, 10);
        props.getThinking().put(ModelTier.FAST, "LOW");

        adapter().call(request(ModelTier.FAST, "p", 512, 1, null));

        JsonNode config = MAPPER.readTree(provider.only().body()).path("generationConfig");
        assertThat(config.path("thinkingConfig").path("thinkingLevel").asText()).isEqualTo("low");
        assertThat(config.path("maxOutputTokens").asInt())
                .as("a level carries no token count, so nothing is added")
                .isEqualTo(512);
    }

    @Test
    @DisplayName("a thinking budget is added on top of the operation's output ceiling")
    void addsTheThinkingBudgetToTheOutputCeiling() throws Exception {
        provider.enqueueSuccess("{\"a\":1}", 10, 10);
        props.getThinking().put(ModelTier.QUALITY, "BUDGET:2048");

        adapter().call(request(ModelTier.QUALITY, "p", 5000, 1, null));

        JsonNode config = MAPPER.readTree(provider.only().body()).path("generationConfig");
        assertThat(config.path("thinkingConfig").path("thinkingBudget").asInt()).isEqualTo(2048);
        // Otherwise the model can spend the lesson's whole allowance reasoning
        // and return MAX_TOKENS with nothing in it - paid for, and worthless.
        assertThat(config.path("maxOutputTokens").asInt()).isEqualTo(7048);
    }

    @Test
    @DisplayName("a retry tells the model why the previous answer was rejected")
    void sendsValidationFeedbackOnRetry() throws Exception {
        provider.enqueueSuccess("{\"questions\":[]}", 10, 10);

        adapter().call(request(ModelTier.QUALITY, "original prompt", 512, 2,
                "question 3 had two correct answers"));

        String userContent = MAPPER.readTree(provider.only().body())
                .path("contents").get(0).path("parts").get(0).path("text").asText();
        assertThat(userContent)
                .as("resending an identical prompt buys an identical rejection at the same price")
                .contains("original prompt")
                .contains("question 3 had two correct answers");
    }

    // ------------------------------------------------------ response mapping

    @Test
    @DisplayName("extracts the object, the served model, and usage")
    void mapsTheResponse() {
        provider.enqueueSuccess("{\"blocks\":[{\"type\":\"overview\"}]}", 1234, 567,
                "gemini-resolved-version");

        LlmPort.Response response = adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null));

        assertThat(response.rawOutput()).isEqualTo("{\"blocks\":[{\"type\":\"overview\"}]}");
        assertThat(response.tokensIn()).isEqualTo(1234);
        assertThat(response.tokensOut()).isEqualTo(567);
        assertThat(response.model())
                .as("the ledger must record the model the provider actually served")
                .isEqualTo("gemini-resolved-version");
        assertThat(response.latencyMs()).isPositive();
    }

    @Test
    @DisplayName("thinking tokens are counted as output, cached tokens as input")
    void countsThinkingAndCachedTokens() {
        provider.enqueue(200, """
                {"modelVersion":"m",
                 "candidates":[{"finishReason":"STOP",
                   "content":{"parts":[{"text":"{\\"a\\":1}"}]}}],
                 "usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":900,
                                  "candidatesTokenCount":40,"thoughtsTokenCount":600}}""");

        LlmPort.Response response = adapter().call(request(ModelTier.FAST, "p", 512, 1, null));

        assertThat(response.tokensIn()).isEqualTo(1000);
        assertThat(response.tokensOut())
                .as("thinking is billed as output; omitting it under-reports the expensive calls")
                .isEqualTo(640);
    }

    @Test
    @DisplayName("thought parts are not concatenated into the payload")
    void ignoresThoughtParts() {
        provider.enqueue(200, """
                {"modelVersion":"m",
                 "candidates":[{"finishReason":"STOP","content":{"parts":[
                   {"text":"I should think about this first.","thought":true},
                   {"text":"{\\"blocks\\":[]}"}]}}],
                 "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1}}""");

        assertThat(adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)).rawOutput())
                .as("a thought summary handed to the validator is prose where JSON was expected")
                .isEqualTo("{\"blocks\":[]}");
    }

    @Test
    @DisplayName("a code fence is stripped rather than sent on to fail as a parse error")
    void stripsACodeFence() {
        provider.enqueueSuccess("```json\n{\"blocks\":[]}\n```", 10, 10);

        assertThat(adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)).rawOutput())
                .isEqualTo("{\"blocks\":[]}");
    }

    @Test
    @DisplayName("truncated output is returned, not thrown, so the tokens it cost are recorded")
    void returnsTruncatedOutputSoItIsStillBilled() {
        provider.enqueue(200, """
                {"modelVersion":"m",
                 "candidates":[{"finishReason":"MAX_TOKENS",
                   "content":{"parts":[{"text":"{\\"blocks\\":[{\\"type\\""}]}}],
                 "usageMetadata":{"promptTokenCount":8000,"candidatesTokenCount":4096}}""");

        LlmPort.Response response = adapter().call(request(ModelTier.QUALITY, "p", 4096, 1, null));

        // The gateway will reject this as a parse failure. That is the point:
        // throwing here would hide 12k tokens of real spend from the ledger.
        assertThat(response.tokensOut()).isEqualTo(4096);
        assertThat(response.rawOutput()).doesNotContain("}");
    }

    @Test
    @DisplayName("a safety block is a failure, not an empty lesson")
    void treatsASafetyBlockAsAPermanentFailure() {
        provider.enqueue(200, """
                {"modelVersion":"m",
                 "candidates":[{"finishReason":"SAFETY","content":{"parts":[]}}],
                 "usageMetadata":{"promptTokenCount":500,"candidatesTokenCount":0}}""");

        assertThatThrownBy(() -> adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(LlmException.Kind.PERMANENT);
                    assertThat(e.providerErrorType()).isEqualTo("content_blocked");
                    assertThat(e).hasMessageContaining("SAFETY");
                });
    }

    @Test
    @DisplayName("a prompt refused before generation is a failure, not a 200 with no content")
    void treatsAPromptBlockAsAPermanentFailure() {
        provider.enqueue(200, """
                {"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},
                 "usageMetadata":{"promptTokenCount":500}}""");

        assertThatThrownBy(() -> adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(LlmException.Kind.PERMANENT);
                    assertThat(e.providerErrorType()).isEqualTo("prompt_blocked");
                });
    }

    // --------------------------------------------------- retry classification

    @Test
    @DisplayName("a quota failure is retried, honouring the provider's retryDelay")
    void retriesAQuotaFailure() {
        provider.enqueue(429, """
                {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
                 "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo",
                             "retryDelay":"0s"}]}}""");
        provider.enqueueSuccess("{\"blocks\":[]}", 10, 10);

        LlmPort.Response response = adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null));

        assertThat(response.rawOutput()).isEqualTo("{\"blocks\":[]}");
        assertThat(provider.requestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a quota wait the provider asks for is honoured beyond our own backoff cap")
    void honoursAProviderDirectedWaitLongerThanTheBackoffCap() {
        // Our backoff is a guess and is capped tightly; the provider's retryDelay
        // is a fact about when the quota window reopens. Clamping the second to
        // the first turns a recoverable pause into a reported outage.
        props.setMaxBackoffMs(5);
        props.setMaxRetryAfterMs(400);
        provider.enqueue(429, """
                {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
                 "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo",
                             "retryDelay":"0.2s"}]}}""");
        provider.enqueueSuccess("{\"blocks\":[]}", 10, 10);

        long start = System.nanoTime();
        adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(provider.requestCount()).isEqualTo(2);
        assertThat(elapsedMs)
                .as("the adapter must actually wait the window out, not retry into it")
                .isGreaterThanOrEqualTo(200);
    }

    @Test
    @DisplayName("a provider-directed wait is still bounded, so a long one fails fast")
    void boundsAProviderDirectedWait() {
        props.setMaxRetryAfterMs(30);
        props.setMaxTransportAttempts(2);
        provider.enqueue(429, """
                {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
                 "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo",
                             "retryDelay":"600s"}]}}""");
        provider.enqueueSuccess("{\"blocks\":[]}", 10, 10);

        long start = System.nanoTime();
        adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("a provider asking for ten minutes must not hold the worker for ten minutes")
                .isLessThan(5_000);
    }

    @Test
    @DisplayName("a server error is retried")
    void retriesAServerError() {
        provider.enqueue(503, "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\"}}");
        provider.enqueueSuccess("{\"blocks\":[]}", 10, 10);

        adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null));

        assertThat(provider.requestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a bad credential is not retried")
    void doesNotRetryAnAuthenticationFailure() {
        provider.enqueue(403, "{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\"}}");
        provider.enqueue(403, "{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\"}}");

        assertThatThrownBy(() -> adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(LlmException.Kind.PERMANENT);
                    assertThat(e.httpStatus()).isEqualTo(403);
                    assertThat(e.providerErrorType()).isEqualTo("PERMISSION_DENIED");
                });

        assertThat(provider.requestCount())
                .as("retrying a bad key three times only fails three times as slowly")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a retired or unavailable model is reported by name and not retried")
    void doesNotRetryAnUnknownModel() {
        props.getModels().put(ModelTier.FAST, "gemini-model-that-was-retired");
        provider.enqueue(404, "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\"}}");

        assertThatThrownBy(() -> adapter().call(request(ModelTier.FAST, "p", 512, 1, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    // Otherwise a model retirement reads as a mysterious outage.
                    assertThat(e).hasMessageContaining("gemini-model-that-was-retired");
                });
        assertThat(provider.requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed request is not retried")
    void doesNotRetryABadRequest() {
        provider.enqueue(400, "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\"}}");

        assertThatThrownBy(() -> adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.retryable()).isFalse());
        assertThat(provider.requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("exhausted transport retries are reported as permanent, not retryable")
    void reportsExhaustedRetriesAsPermanent() {
        props.setMaxTransportAttempts(3);
        for (int i = 0; i < 3; i++) {
            provider.enqueue(503, "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\"}}");
        }

        assertThatThrownBy(() -> adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(LlmException.Kind.PERMANENT);
                    assertThat(e).hasMessageContaining("3 transport attempts");
                });
        assertThat(provider.requestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a provider that never answers times out instead of holding the worker")
    void timesOut() {
        props.setRequestTimeoutMs(200);
        props.setMaxTransportAttempts(1);
        provider.enqueueHang(800);

        assertThatThrownBy(() -> adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(LlmException.Kind.PERMANENT);
                    assertThat(e).hasMessageContaining("did not respond");
                });
    }

    // ------------------------------------------------------------ cancellation

    @Test
    @DisplayName("an already-interrupted thread does not reach the provider at all")
    void refusesToCallWhenAlreadyCancelled() {
        provider.enqueueSuccess("{\"blocks\":[]}", 10, 10);
        GeminiLlmAdapter adapter = adapter();

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> adapter.call(request(ModelTier.QUALITY, "p", 512, 1, null)))
                    .isInstanceOfSatisfying(LlmException.class,
                            e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.CANCELLED));
        } finally {
            Thread.interrupted();
        }
        assertThat(provider.requestCount()).isZero();
    }

    @Test
    @DisplayName("an in-flight call is cancelled when the worker thread is interrupted")
    void cancelsAnInFlightCall() throws Exception {
        provider.enqueueHang(1500);
        GeminiLlmAdapter adapter = adapter();

        AtomicReference<LlmException> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                adapter.call(request(ModelTier.QUALITY, "p", 512, 1, null));
            } catch (LlmException e) {
                thrown.set(e);
            }
        });
        caller.start();

        assertThat(provider.awaitFirstRequest(5, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(10_000);

        assertThat(caller.isAlive()).as("a cancelled call must not hold the thread").isFalse();
        assertThat(thrown.get()).isNotNull();
        assertThat(thrown.get().kind()).isEqualTo(LlmException.Kind.CANCELLED);
    }

    // ------------------------------------------------------------ safe logging

    @Test
    @DisplayName("neither the prompt, the output, nor the key is ever written to the log")
    void neverLogsLearnerContentOrCredentials() {
        provider.enqueue(500, "{\"error\":{\"status\":\"INTERNAL\",\"message\":\""
                + SECRET_PROMPT + "\"}}");
        provider.enqueueSuccess("{\"text\":\"" + SECRET_OUTPUT + "\"}", 10, 10);

        adapter().call(request(ModelTier.QUALITY, SECRET_PROMPT, 512, 1, null));

        String written = logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(written)
                .as("the prompt carries the learner's own course material")
                .doesNotContain(SECRET_PROMPT)
                .doesNotContain(SECRET_OUTPUT)
                .doesNotContain(SECRET_KEY);
        // Diagnosis still works: the failure and its shape are recorded.
        assertThat(written).contains("INTERNAL").contains("status=500");
    }

    @Test
    @DisplayName("a provider message is surfaced only when explicitly enabled")
    void logsProviderMessagesOnlyOnRequest() {
        props.setLogProviderMessages(true);
        provider.enqueue(400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\","
                + "\"message\":\"visible detail\"}}");

        assertThatThrownBy(() -> adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)))
                .hasMessageContaining("visible detail");
    }

    // ----------------------------------------------------------------- helpers

    private GeminiLlmAdapter adapter() {
        return new GeminiLlmAdapter(props, MAPPER);
    }

    private static LlmPort.Request request(ModelTier tier, String prompt, int maxTokens,
                                           int attempt, String feedback) {
        return new LlmPort.Request("test.operation", tier, prompt, "v1",
                maxTokens, attempt, feedback, "{}");
    }

    private static ch.qos.logback.classic.Logger logger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GeminiLlmAdapter.class);
    }

    /** A provider that answers exactly what the test queued, and records what it was asked. */
    private static final class StubGemini {

        private final HttpServer server;
        private final Deque<Queued> queued = new ArrayDeque<>();
        private final List<Recorded> received = new ArrayList<>();
        private final CountDownLatch firstRequest = new CountDownLatch(1);

        record Queued(int status, String body, Map<String, String> headers, long hangMs) {
        }

        record Recorded(Map<String, List<String>> headers, String path, String query, String body) {
            /** The JDK's server normalises header names, so match without regard to case. */
            List<String> header(String name) {
                return headers.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(name))
                        .findFirst()
                        .map(Map.Entry::getValue)
                        .orElse(List.of());
            }
        }

        StubGemini() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // Everything under the root, because the model id is part of the path.
            server.createContext("/", this::handle);
            server.setExecutor(null);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            synchronized (this) {
                received.add(new Recorded(
                        Map.copyOf(exchange.getRequestHeaders()),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestURI().getQuery(),
                        new String(requestBody, StandardCharsets.UTF_8)));
            }
            firstRequest.countDown();

            Queued next;
            synchronized (this) {
                next = queued.isEmpty()
                        ? new Queued(500, "{\"error\":{\"status\":\"NO_RESPONSE_QUEUED\"}}", Map.of(), 0)
                        : queued.removeFirst();
            }

            if (next.hangMs() > 0) {
                try {
                    Thread.sleep(next.hangMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            byte[] payload = next.body().getBytes(StandardCharsets.UTF_8);
            next.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.getResponseHeaders().add("content-type", "application/json");
            try {
                exchange.sendResponseHeaders(next.status(), payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            } catch (IOException alreadyGone) {
                // The client timed out or was cancelled. Expected in those tests.
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        synchronized void enqueue(int status, String body) {
            queued.addLast(new Queued(status, body, Map.of(), 0));
        }

        synchronized void enqueueHang(long ms) {
            queued.addLast(new Queued(200, "{}", Map.of(), ms));
        }

        void enqueueSuccess(String text, int tokensIn, int tokensOut) {
            enqueueSuccess(text, tokensIn, tokensOut, "test-model");
        }

        /** Built with Jackson so the test can queue any text without escaping it by hand. */
        synchronized void enqueueSuccess(String text, int tokensIn, int tokensOut, String model) {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("modelVersion", model);
            ObjectNode candidate = root.putArray("candidates").addObject();
            candidate.put("finishReason", "STOP");
            candidate.putObject("content").putArray("parts").addObject().put("text", text);
            ObjectNode usage = root.putObject("usageMetadata");
            usage.put("promptTokenCount", tokensIn);
            usage.put("candidatesTokenCount", tokensOut);
            queued.addLast(new Queued(200, root.toString(), Map.of(), 0));
        }

        synchronized int requestCount() {
            return received.size();
        }

        synchronized Recorded only() {
            assertThat(received).hasSize(1);
            return received.get(0);
        }

        boolean awaitFirstRequest(long timeout, TimeUnit unit) throws InterruptedException {
            return firstRequest.await(timeout, unit);
        }

        void stop() {
            server.stop(0);
        }
    }
}
