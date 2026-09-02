package com.certcopilot.platform.ai.provider;

import java.util.EnumMap;
import java.util.Map;

import com.certcopilot.platform.ai.ModelTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How to reach the model provider. Transport concerns only.
 *
 * <p>What a call <em>costs</em> lives in {@code AiProperties} instead, because
 * the gateway must be able to price an attempt without knowing which adapter
 * produced it - the deterministic fake is priced the same way.
 *
 * <p>The tier-to-model mapping lives here so that domain code never names a
 * model. Switching provider or moving an operation to a cheaper model is a
 * configuration change; the six declared operations do not change.
 */
@ConfigurationProperties(prefix = "app.ai.provider")
public class ProviderProperties {

    private String baseUrl = "https://api.anthropic.com";

    /** Never defaulted to a literal. Supplied by the environment, absent in CI. */
    private String apiKey = "";

    private String anthropicVersion = "2023-06-01";

    private long connectTimeoutMs = 10_000;

    /**
     * Whole-request ceiling. Generous, because a long lesson at a quality tier
     * legitimately takes tens of seconds, and a premature timeout costs the
     * tokens anyway while throwing away the output.
     */
    private long requestTimeoutMs = 120_000;

    /** Transport attempts, including the first. Content retries are the gateway's. */
    private int maxTransportAttempts = 3;

    private long initialBackoffMs = 500;

    private long maxBackoffMs = 8_000;

    /**
     * Whether a provider error message may be written to the log.
     *
     * <p>Off by default: a 400 can quote the offending part of the request, and
     * the request contains the learner's own course material. Status code and
     * error type are always logged and are enough to diagnose.
     */
    private boolean logProviderMessages = false;

    /** Deterministic output. Lessons and quizzes are graded artefacts, not prose to be varied. */
    private double temperature = 0.0;

    private Map<ModelTier, String> models = defaultModels();

    private static Map<ModelTier, String> defaultModels() {
        Map<ModelTier, String> map = new EnumMap<>(ModelTier.class);
        map.put(ModelTier.FAST, "claude-haiku-4-5-20251001");
        map.put(ModelTier.QUALITY, "claude-sonnet-5");
        return map;
    }

    public String modelFor(ModelTier tier) {
        String model = models.get(tier);
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "no model configured for tier " + tier + "; set app.ai.provider.models." + tier);
        }
        return model;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
    public String getAnthropicVersion() { return anthropicVersion; }
    public void setAnthropicVersion(String v) { this.anthropicVersion = v; }
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
    public boolean isLogProviderMessages() { return logProviderMessages; }
    public void setLogProviderMessages(boolean v) { this.logProviderMessages = v; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double v) { this.temperature = v; }
    public Map<ModelTier, String> getModels() { return models; }
    public void setModels(Map<ModelTier, String> v) { this.models = v; }
}
