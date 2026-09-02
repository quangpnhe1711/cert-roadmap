package com.certcopilot.platform.ai;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI platform configuration. The model price table lives here rather than in
 * code so a provider price change is a config change (correction A3 depends on
 * cost being computable at call time).
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** Which LlmPort implementation to activate: "fake" for development and CI, "anthropic" in production. */
    private String adapter = "fake";

    /** Default per-plan hard cap. D6: $10 is the review threshold. */
    private int defaultHardCapCents = 1000;

    /** Default per-plan soft cap. D6: $5 is the target. */
    private int defaultSoftCapCents = 500;

    private long reservationTtlMs = 300_000;

    /** Re-read prompt files on every call. On in dev, off in production. */
    private boolean promptHotReload = false;

    /** Cost per million input tokens, in cents, by tier. */
    private int fastInputCentsPerMillion = 25;
    private int fastOutputCentsPerMillion = 125;
    private int qualityInputCentsPerMillion = 300;
    private int qualityOutputCentsPerMillion = 1500;

    /**
     * Published provider prices in cents per million tokens, keyed by the model id
     * the provider reports back.
     *
     * <p>Ships empty on purpose. Inventing a price would produce a cost report
     * that looks authoritative and is not; with no entry the tier estimate below
     * is used and {@link #hasPublishedPrice} says so, which is what lets a cost
     * report distinguish a measurement from an estimate.
     */
    private Map<String, ModelPrice> modelPrices = new LinkedHashMap<>();

    /** Cents per million tokens for one concrete model. */
    public static class ModelPrice {
        private int inputCentsPerMillion;
        private int outputCentsPerMillion;

        public int getInputCentsPerMillion() { return inputCentsPerMillion; }
        public void setInputCentsPerMillion(int v) { this.inputCentsPerMillion = v; }
        public int getOutputCentsPerMillion() { return outputCentsPerMillion; }
        public void setOutputCentsPerMillion(int v) { this.outputCentsPerMillion = v; }
    }

    /** True when this model's real price is configured, rather than estimated from its tier. */
    public boolean hasPublishedPrice(String model) {
        return model != null && modelPrices.containsKey(model);
    }

    /**
     * Cost of one attempt, rounded up so a cheap call is never recorded as free.
     * Free calls would make the ledger under-report exactly the high-retry period
     * we most need to measure.
     *
     * <p>Uses the model's published price when one is configured and the tier
     * estimate otherwise, so switching a tier to a different model changes the
     * ledger without a code change.
     */
    public int costCents(String model, ModelTier tier, int tokensIn, int tokensOut) {
        ModelPrice price = model == null ? null : modelPrices.get(model);
        int inRate = price != null ? price.getInputCentsPerMillion()
                : tier == ModelTier.QUALITY ? qualityInputCentsPerMillion : fastInputCentsPerMillion;
        int outRate = price != null ? price.getOutputCentsPerMillion()
                : tier == ModelTier.QUALITY ? qualityOutputCentsPerMillion : fastOutputCentsPerMillion;
        long micros = (long) tokensIn * inRate + (long) tokensOut * outRate;
        long cents = (micros + 999_999) / 1_000_000;
        return (int) Math.max(tokensIn + tokensOut > 0 ? 1 : 0, cents);
    }

    /** Tier-only pricing, for callers that have no model id. */
    public int costCents(ModelTier tier, int tokensIn, int tokensOut) {
        return costCents(null, tier, tokensIn, tokensOut);
    }

    public String getAdapter() { return adapter; }
    public void setAdapter(String v) { this.adapter = v; }
    public int getDefaultHardCapCents() { return defaultHardCapCents; }
    public void setDefaultHardCapCents(int v) { this.defaultHardCapCents = v; }
    public int getDefaultSoftCapCents() { return defaultSoftCapCents; }
    public void setDefaultSoftCapCents(int v) { this.defaultSoftCapCents = v; }
    public long getReservationTtlMs() { return reservationTtlMs; }
    public void setReservationTtlMs(long v) { this.reservationTtlMs = v; }
    public boolean isPromptHotReload() { return promptHotReload; }
    public void setPromptHotReload(boolean v) { this.promptHotReload = v; }
    public int getFastInputCentsPerMillion() { return fastInputCentsPerMillion; }
    public void setFastInputCentsPerMillion(int v) { this.fastInputCentsPerMillion = v; }
    public int getFastOutputCentsPerMillion() { return fastOutputCentsPerMillion; }
    public void setFastOutputCentsPerMillion(int v) { this.fastOutputCentsPerMillion = v; }
    public int getQualityInputCentsPerMillion() { return qualityInputCentsPerMillion; }
    public void setQualityInputCentsPerMillion(int v) { this.qualityInputCentsPerMillion = v; }
    public int getQualityOutputCentsPerMillion() { return qualityOutputCentsPerMillion; }
    public void setQualityOutputCentsPerMillion(int v) { this.qualityOutputCentsPerMillion = v; }
    public Map<String, ModelPrice> getModelPrices() { return modelPrices; }
    public void setModelPrices(Map<String, ModelPrice> v) { this.modelPrices = v; }
}
