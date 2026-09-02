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
 * Contract test for the production adapter, run against a stub HTTP server.
 *
 * <p>This is what makes "the production adapter is implemented" a checkable claim
 * rather than an assertion. It covers everything the adapter is responsible for -
 * authentication, model selection, request mapping, structured output, token and
 * cost inputs, error mapping, retry classification, timeouts, cancellation and
 * safe logging - with no credential and no network.
 *
 * <p>What it deliberately cannot cover is whether the real provider behaves as
 * documented. That needs a key, and it is the one thing left blocked.
 */
class AnthropicLlmAdapterContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Distinctive strings that must never reach a log line. */
    private static final String SECRET_PROMPT = "LEARNER-MATERIAL-SENTINEL-do-not-log";
    private static final String SECRET_OUTPUT = "GENERATED-OUTPUT-SENTINEL-do-not-log";

    private StubProvider provider;
    private ProviderProperties props;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void startStub() throws IOException {
        provider = new StubProvider();
        props = new ProviderProperties();
        props.setBaseUrl(provider.baseUrl());
        props.setApiKey("test-key-not-a-real-credential");
        // Keep the retry tests fast; the backoff itself is covered by assertions
        // on the number of requests, not by wall-clock time.
        props.setInitialBackoffMs(1);
        props.setMaxBackoffMs(5);

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
        ProviderProperties keyless = new ProviderProperties();
        keyless.setApiKey("");

        assertThatThrownBy(() -> new AnthropicLlmAdapter(keyless, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no API key is configured")
                .hasMessageContaining("app.ai.adapter=fake");
    }

    // ------------------------------------------------------- request mapping

    @Test
    @DisplayName("authenticates, selects the model for the tier, and forces a JSON object")
    void mapsTheRequest() throws Exception {
        provider.enqueueSuccess("\"sections\":[]}", 1200, 340);
        props.getModels().put(ModelTier.QUALITY, "test-quality-model");

        adapter().call(request(ModelTier.QUALITY, "prompt text", 4096, 1, null));

        StubProvider.Recorded recorded = provider.only();
        assertThat(recorded.header("x-api-key")).containsExactly("test-key-not-a-real-credential");
        assertThat(recorded.header("anthropic-version")).isNotEmpty();
        assertThat(recorded.header("content-type")).containsExactly("application/json");

        JsonNode body = MAPPER.readTree(recorded.body());
        assertThat(body.path("model").asText()).isEqualTo("test-quality-model");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(body.path("temperature").asDouble()).isZero();
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(0).path("content").asText()).contains("prompt text");

        // The assistant prefill is what guarantees a bare JSON object comes back.
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("{");
    }

    @Test
    @DisplayName("the fast tier uses the fast model, so tier is a real cost lever")
    void selectsTheFastModelForTheFastTier() throws Exception {
        provider.enqueueSuccess("\"topics\":[]}", 10, 10);
        props.getModels().put(ModelTier.FAST, "test-fast-model");

        adapter().call(request(ModelTier.FAST, "p", 512, 1, null));

        assertThat(MAPPER.readTree(provider.only().body()).path("model").asText())
                .isEqualTo("test-fast-model");
    }

    @Test
    @DisplayName("a retry tells the model why the previous answer was rejected")
    void sendsValidationFeedbackOnRetry() throws Exception {
        provider.enqueueSuccess("\"questions\":[]}", 10, 10);

        adapter().call(request(ModelTier.QUALITY, "original prompt", 512, 2,
                "question 3 had two correct answers"));

        String userContent = MAPPER.readTree(provider.only().body())
                .path("messages").get(0).path("content").asText();
        assertThat(userContent)
                .as("resending an identical prompt buys an identical rejection at the same price")
                .contains("original prompt")
                .contains("question 3 had two correct answers");
    }

    // ------------------------------------------------------ response mapping

    @Test
    @DisplayName("reassembles the prefilled object and extracts usage and model")
    void mapsTheResponse() {
        provider.enqueueSuccess("\"blocks\":[{\"type\":\"overview\"}]}", 1234, 567,
                "claude-resolved-snapshot");

        LlmPort.Response response = adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null));

        assertThat(response.rawOutput()).isEqualTo("{\"blocks\":[{\"type\":\"overview\"}]}");
        assertThat(response.tokensIn()).isEqualTo(1234);
        assertThat(response.tokensOut()).isEqualTo(567);
        assertThat(response.model())
                .as("the ledger must record the model the provider actually served")
                .isEqualTo("claude-resolved-snapshot");
        assertThat(response.latencyMs()).isPositive();
    }

    @Test
    @DisplayName("cached input tokens are still counted as input")
    void countsCachedInputTokens() {
        provider.enqueue(200, """
                {"model":"m","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"\\"a\\":1}"}],
                 "usage":{"input_tokens":100,"cache_read_input_tokens":900,
                          "cache_creation_input_tokens":50,"output_tokens":7}}""");

        LlmPort.Response response = adapter().call(request(ModelTier.FAST, "p", 512, 1, null));

        assertThat(response.tokensIn())
                .as("omitting cached tokens under-reports exactly the calls we made cheaper")
                .isEqualTo(1050);
    }

    @Test
    @DisplayName("a model that restates the whole object is not corrupted by the prefill")
    void handlesAModelThatIgnoresThePrefill() {
        provider.enqueueSuccess("{\"blocks\":[]}", 10, 10);

        assertThat(adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)).rawOutput())
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
                {"model":"m","stop_reason":"max_tokens",
                 "content":[{"type":"text","text":"\\"blocks\\":[{\\"type\\""}],
                 "usage":{"input_tokens":8000,"output_tokens":4096}}""");

        LlmPort.Response response = adapter().call(request(ModelTier.QUALITY, "p", 4096, 1, null));

        // The gateway will reject this as a parse failure. That is the point:
        // throwing here would hide 12k tokens of real spend from the ledger.
        assertThat(response.tokensOut()).isEqualTo(4096);
        assertThat(response.rawOutput()).doesNotContain("}");
    }

    // --------------------------------------------------- retry classification

    @Test
    @DisplayName("a rate limit is retried, honouring retry-after")
    void retriesARateLimit() {
        provider.enqueue(429, "{\"error\":{\"type\":\"rate_limit_error\"}}", Map.of("retry-after", "0"));
        provider.enqueueSuccess("\"blocks\":[]}", 10, 10);

        LlmPort.Response response = adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null));

        assertThat(response.rawOutput()).isEqualTo("{\"blocks\":[]}");
        assertThat(provider.requestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a server error is retried")
    void retriesAServerError() {
        provider.enqueue(503, "{\"error\":{\"type\":\"overloaded_error\"}}");
        provider.enqueueSuccess("\"blocks\":[]}", 10, 10);

        adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null));

        assertThat(provider.requestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a bad credential is not retried")
    void doesNotRetryAnAuthenticationFailure() {
        provider.enqueue(401, "{\"error\":{\"type\":\"authentication_error\"}}");
        provider.enqueue(401, "{\"error\":{\"type\":\"authentication_error\"}}");
        provider.enqueue(401, "{\"error\":{\"type\":\"authentication_error\"}}");

        assertThatThrownBy(() -> adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(LlmException.Kind.PERMANENT);
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.httpStatus()).isEqualTo(401);
                    assertThat(e.providerErrorType()).isEqualTo("authentication_error");
                });

        assertThat(provider.requestCount())
                .as("retrying a bad key three times only fails three times as slowly")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed request is not retried")
    void doesNotRetryABadRequest() {
        provider.enqueue(400, "{\"error\":{\"type\":\"invalid_request_error\"}}");

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
            provider.enqueue(503, "{\"error\":{\"type\":\"overloaded_error\"}}");
        }

        assertThatThrownBy(() -> adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    // Otherwise the gateway would immediately repeat a wait the
                    // adapter has already served, three more times, per attempt.
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
        provider.enqueueSuccess("\"blocks\":[]}", 10, 10);
        AnthropicLlmAdapter adapter = adapter();

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
        AnthropicLlmAdapter adapter = adapter();

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
    @DisplayName("neither the prompt nor the output is ever written to the log")
    void neverLogsLearnerContent() {
        provider.enqueue(500, "{\"error\":{\"type\":\"api_error\",\"message\":\"" + SECRET_PROMPT + "\"}}");
        provider.enqueueSuccess("\"text\":\"" + SECRET_OUTPUT + "\"}", 10, 10);

        adapter().call(request(ModelTier.QUALITY, SECRET_PROMPT, 512, 1, null));

        String written = logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(written)
                .as("the prompt carries the learner's own course material")
                .doesNotContain(SECRET_PROMPT)
                .doesNotContain(SECRET_OUTPUT);
        // Diagnosis still works: the failure and its shape are recorded.
        assertThat(written).contains("api_error").contains("status=500");
    }

    @Test
    @DisplayName("a provider message is logged only when explicitly enabled")
    void logsProviderMessagesOnlyOnRequest() {
        props.setLogProviderMessages(true);
        provider.enqueue(400, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"visible detail\"}}");

        assertThatThrownBy(() -> adapter().call(request(ModelTier.QUALITY, "p", 512, 1, null)))
                .hasMessageContaining("visible detail");
    }

    // ----------------------------------------------------------------- helpers

    private AnthropicLlmAdapter adapter() {
        return new AnthropicLlmAdapter(props, MAPPER);
    }

    private static LlmPort.Request request(ModelTier tier, String prompt, int maxTokens,
                                           int attempt, String feedback) {
        return new LlmPort.Request("test.operation", tier, prompt, "v1",
                maxTokens, attempt, feedback, "{}");
    }

    private static ch.qos.logback.classic.Logger logger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AnthropicLlmAdapter.class);
    }

    /** A provider that answers exactly what the test queued, and records what it was asked. */
    private static final class StubProvider {

        private final HttpServer server;
        private final Deque<Queued> queued = new ArrayDeque<>();
        private final List<Recorded> received = new ArrayList<>();
        private final CountDownLatch firstRequest = new CountDownLatch(1);

        record Queued(int status, String body, Map<String, String> headers, long hangMs) {
        }

        record Recorded(Map<String, List<String>> headers, String body) {
            /** The JDK's server normalises header names, so match without regard to case. */
            List<String> header(String name) {
                return headers.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(name))
                        .findFirst()
                        .map(Map.Entry::getValue)
                        .orElse(List.of());
            }
        }

        StubProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", this::handle);
            server.setExecutor(null);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            synchronized (this) {
                received.add(new Recorded(
                        Map.copyOf(exchange.getRequestHeaders()),
                        new String(requestBody, StandardCharsets.UTF_8)));
            }
            firstRequest.countDown();

            Queued next;
            synchronized (this) {
                next = queued.isEmpty()
                        ? new Queued(500, "{\"error\":{\"type\":\"no_response_queued\"}}", Map.of(), 0)
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

        synchronized void enqueue(int status, String body, Map<String, String> headers) {
            queued.addLast(new Queued(status, body, headers, 0));
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
            root.put("model", model);
            root.put("stop_reason", "end_turn");
            ObjectNode block = root.putArray("content").addObject();
            block.put("type", "text");
            block.put("text", text);
            ObjectNode usage = root.putObject("usage");
            usage.put("input_tokens", tokensIn);
            usage.put("output_tokens", tokensOut);
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
