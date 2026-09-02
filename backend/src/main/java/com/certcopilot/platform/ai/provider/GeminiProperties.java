package com.certcopilot.platform.ai.provider;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import com.certcopilot.platform.ai.ModelTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How to reach Google's Gemini API. Transport and sampling concerns only.
 *
 * <p>Separate from {@link ProviderProperties} rather than shared with it: the two
 * providers disagree about the things that matter here - endpoint shape, auth
 * header, version pinning, and how thinking is budgeted - and one class covering
 * both would carry each provider's fields as dead weight and need prose to
 * explain which half applies.
 *
 * <p>What a call <em>costs</em> still lives in {@code AiProperties}, keyed by the
 * model id the provider reports back, so the ledger prices a Gemini attempt the
 * same way it prices any other.
 */
@ConfigurationProperties(prefix = "app.ai.provider.gemini")
public class GeminiProperties {

    private String baseUrl = "https://generativelanguage.googleapis.com";

    /**
     * API version segment. Pinned rather than "latest", because request and
     * response shapes differ between versions and a silent upgrade would change
     * how usage is reported without changing any code here.
     */
    private String apiVersion = "v1beta";

    /** Never defaulted to a literal. Supplied by the environment, absent in CI. */
    private String apiKey = "";

    private long connectTimeoutMs = 10_000;

    /**
     * Whole-request ceiling. Generous, because a long lesson at the quality tier
     * legitimately takes tens of seconds, and a premature timeout costs the
     * tokens anyway while throwing away the output.
     */
    private long requestTimeoutMs = 120_000;

    /**
     * Transport attempts, including the first. Content retries are the gateway's.
     *
     * <p>Five rather than three because Gemini's free tier limits requests per
     * minute, and a plan is dozens of calls: a run that gives up after two waits
     * reports an outage when what actually happened is that the next minute had
     * not started yet. Extra attempts cost nothing on a paid key, where 429s are
     * rare, and only ever follow a failure that spent no tokens.
     */
    private int maxTransportAttempts = 5;

    private long initialBackoffMs = 500;

    private long maxBackoffMs = 8_000;

    /**
     * Ceiling for a wait the <em>provider</em> asked for, as opposed to one this
     * adapter guessed.
     *
     * <p>Separate from {@link #maxBackoffMs} because the two are different kinds
     * of number. Our own backoff is a guess and deserves a tight cap. A 429
     * carrying {@code retryDelay: 48s} is a fact: the quota window is 48 seconds
     * from reopening, and clamping that to 8 seconds guarantees three attempts
     * that were always going to fail, reported as an outage. Generous rather than
     * unbounded, so a provider asking for ten minutes still fails fast.
     */
    private long maxRetryAfterMs = 90_000;

    /**
     * Whether a provider error message may be written to the log. Off by default:
     * a 400 can quote the offending part of the request, and the request contains
     * the learner's own course material.
     */
    private boolean logProviderMessages = false;

    /** Deterministic output. Lessons and quizzes are graded artefacts, not prose to be varied. */
    private double temperature = 0.0;

    /**
     * Model per tier. Configuration, never a literal in domain code, so moving an
     * operation to a different model is a config change. A retired model answers
     * 404, which the adapter reports as a permanent failure naming the model
     * rather than as a mysterious outage.
     *
     * <p>The defaults are current-generation on purpose. Google withdraws older
     * ids from new keys - {@code gemini-2.5-pro} and {@code gemini-2.5-flash-lite}
     * already answer 404 with "no longer available to new users" - so a default
     * pinned to a previous generation is a default with an expiry date. Both were
     * checked against the live API rather than taken from documentation.
     */
    private Map<ModelTier, String> models = defaultModels();

    /**
     * How much thinking each tier is allowed. Gemini charges thinking tokens as
     * output tokens and counts them against {@code maxOutputTokens}, so leaving
     * this unconfigured is how a lesson gets truncated after the model has spent
     * the whole budget reasoning about it.
     *
     * <p>Accepted values, because the two model families disagree on the knob:
     *
     * <ul>
     *   <li>{@code NONE} - send no thinking configuration, take the model's default</li>
     *   <li>{@code LOW} / {@code HIGH} - {@code thinkingLevel}, understood by the 3.x models</li>
     *   <li>{@code BUDGET:n} - {@code thinkingBudget}, understood by the 2.5 models and by
     *       3.x flash; {@code BUDGET:0} turns thinking off</li>
     * </ul>
     *
     * <p>When a budget is given the adapter raises {@code maxOutputTokens} by it,
     * so the operation's declared ceiling stays a ceiling on the answer rather
     * than on the answer plus the reasoning.
     */
    private Map<ModelTier, String> thinking = defaultThinking();

    private static Map<ModelTier, String> defaultModels() {
        Map<ModelTier, String> map = new EnumMap<>(ModelTier.class);
        map.put(ModelTier.FAST, "gemini-3.5-flash-lite");
        map.put(ModelTier.QUALITY, "gemini-3.7-flash");
        return map;
    }

    private static Map<ModelTier, String> defaultThinking() {
        Map<ModelTier, String> map = new EnumMap<>(ModelTier.class);
        map.put(ModelTier.FAST, "LOW");
        map.put(ModelTier.QUALITY, "BUDGET:0");
        return map;
    }

    public String modelFor(ModelTier tier) {
        String model = models.get(tier);
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "no Gemini model configured for tier " + tier
                            + "; set app.ai.provider.gemini.models." + tier);
        }
        return model;
    }

    /** A tier's thinking setting, already interpreted. */
    public Thinking thinkingFor(ModelTier tier) {
        return Thinking.parse(thinking.get(tier));
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * @param mode   which knob to send, if any
     * @param budget token budget when {@link Mode#BUDGET}; ignored otherwise
     * @param level  {@code thinkingLevel} value when {@link Mode#LEVEL}
     */
    public record Thinking(Mode mode, int budget, String level) {

        public enum Mode { DEFAULT, LEVEL, BUDGET }

        static Thinking parse(String raw) {
            String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (value.isEmpty() || value.equals("NONE") || value.equals("DEFAULT")) {
                return new Thinking(Mode.DEFAULT, 0, null);
            }
            if (value.startsWith("BUDGET:")) {
                String number = value.substring("BUDGET:".length()).trim();
                try {
                    return new Thinking(Mode.BUDGET, Math.max(0, Integer.parseInt(number)), null);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                            "app.ai.provider.gemini.thinking expects BUDGET:<number>, got " + raw);
                }
            }
            if (value.equals("LOW") || value.equals("HIGH")) {
                return new Thinking(Mode.LEVEL, 0, value.toLowerCase(Locale.ROOT));
            }
            throw new IllegalStateException(
                    "app.ai.provider.gemini.thinking expects NONE, LOW, HIGH or BUDGET:<number>, got "
                            + raw);
        }

        /** Extra output tokens to ask for, so thinking does not eat the answer's budget. */
        public int extraOutputTokens() {
            return mode == Mode.BUDGET ? budget : 0;
        }
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String v) { this.apiVersion = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(long v) { this.connectTimeoutMs = v; }
    public long getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(long v) { this.requestTimeoutMs = v; }
    public int getMaxTransportAttempts() { return maxTransportAttempts; }
    public void setMaxTransportAttempts(int v) { this.maxTransportAttempts = v; }
    public long getInitialBackoffMs() { return initialBackoffMs; }
    public void setInitialBackoffMs(long v) { this.initialBackoffMs = v; }
    public long getMaxBackoffMs() { return maxBackoffMs; }
    public void setMaxBackoffMs(long v) { this.maxBackoffMs = v; }
    public long getMaxRetryAfterMs() { return maxRetryAfterMs; }
    public void setMaxRetryAfterMs(long v) { this.maxRetryAfterMs = v; }
    public boolean isLogProviderMessages() { return logProviderMessages; }
    public void setLogProviderMessages(boolean v) { this.logProviderMessages = v; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double v) { this.temperature = v; }
    public Map<ModelTier, String> getModels() { return models; }
    public void setModels(Map<ModelTier, String> v) { this.models = v; }
    public Map<ModelTier, String> getThinking() { return thinking; }
    public void setThinking(Map<ModelTier, String> v) { this.thinking = v; }
}
