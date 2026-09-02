package com.certcopilot.shared;

/** Set by the compression ladder; reduces both study time and generated length. */
public enum DepthFlag {
    FULL(1.0),
    CONDENSED(0.7);

    private final double effortMultiplier;

    DepthFlag(double effortMultiplier) {
        this.effortMultiplier = effortMultiplier;
    }

    public double effortMultiplier() {
        return effortMultiplier;
    }
}
