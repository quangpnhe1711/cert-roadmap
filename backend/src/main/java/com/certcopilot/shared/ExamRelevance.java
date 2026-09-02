package com.certcopilot.shared;

/**
 * How much a piece of learning content matters for the exam.
 *
 * <p>This is a property of the pair (learning unit, certification), which is why
 * it lives on the mapping and never on the unit itself (correction A1).
 */
public enum ExamRelevance {
    CRITICAL(1.35),
    HIGH(1.15),
    MEDIUM(1.0),
    LOW(0.8),
    OPTIONAL(0.6);

    private final double effortWeight;

    ExamRelevance(double effortWeight) {
        this.effortWeight = effortWeight;
    }

    /** Multiplier applied to base effort when resolving plan-level effort. */
    public double effortWeight() {
        return effortWeight;
    }

    public boolean atMost(ExamRelevance other) {
        return ordinal() >= other.ordinal();
    }
}
