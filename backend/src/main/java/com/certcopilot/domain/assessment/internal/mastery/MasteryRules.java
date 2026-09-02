package com.certcopilot.domain.assessment.internal.mastery;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.certcopilot.shared.MasteryStatus;

/**
 * Deterministic mastery and weakness rules.
 *
 * <p>Architecture rule R3 keeps this package away from {@code platform.ai}. A
 * model may explain <em>why</em> a learner is struggling; it may never decide
 * <em>whether</em> they are. Self-rated confidence is also absent by design - it
 * is a noisy signal and answer history is simply better evidence.
 *
 * <p>Pure functions, so the same history always yields the same status.
 */
public final class MasteryRules {

    /** Below this a topic needs revisiting. */
    public static final double WEAK_ACCURACY = 0.70;

    /** Sustained accuracy required before a topic counts as mastered. */
    public static final double MASTERY_ACCURACY = 0.85;

    /** Minimum answers before accuracy means anything at all. */
    public static final int MIN_ANSWERS_FOR_WEAKNESS = 3;

    public static final int MIN_ANSWERS_FOR_MASTERY = 5;

    /** Mastery needs evidence across time, not one lucky session. */
    public static final int MIN_SESSIONS_FOR_MASTERY = 2;

    public static final int MIN_DAYS_BETWEEN_SESSIONS = 1;

    private MasteryRules() {
    }

    public static MasteryStatus status(Evidence evidence) {
        if (evidence.totalCount() == 0 && !evidence.unitCompleted()) {
            return MasteryStatus.NOT_STARTED;
        }
        double accuracy = evidence.accuracy();

        if (isMastered(evidence)) {
            return MasteryStatus.MASTERED;
        }
        if (evidence.totalCount() >= MIN_ANSWERS_FOR_WEAKNESS && accuracy < WEAK_ACCURACY) {
            return MasteryStatus.REVIEW;
        }
        return MasteryStatus.LEARNING;
    }

    /**
     * The two-session rule is what makes MASTERED mean something. Without it a
     * learner who happens to get five questions right in one sitting is declared
     * finished with a topic they have not retained.
     */
    static boolean isMastered(Evidence evidence) {
        if (evidence.totalCount() < MIN_ANSWERS_FOR_MASTERY) {
            return false;
        }
        if (evidence.accuracy() < MASTERY_ACCURACY) {
            return false;
        }
        if (evidence.sessionsCount() < MIN_SESSIONS_FOR_MASTERY) {
            return false;
        }
        if (evidence.firstSessionOn() == null || evidence.lastSessionOn() == null) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(evidence.firstSessionOn(), evidence.lastSessionOn());
        return days >= MIN_DAYS_BETWEEN_SESSIONS;
    }

    /**
     * Tier two of weak-topic detection: enough evidence to spend schedule time on.
     *
     * @param domainWeightPercent weight of the exam domain this topic serves
     */
    public static boolean isWeak(Evidence evidence, int domainWeightPercent, int mistakesLast7Days) {
        if (evidence.totalCount() >= MIN_ANSWERS_FOR_WEAKNESS
                && evidence.accuracy() < WEAK_ACCURACY) {
            return true;
        }
        if (mistakesLast7Days >= 2) {
            return true;
        }
        // A heavily weighted domain deserves a lower bar: being merely adequate
        // on 28% of the exam is a bigger risk than being weak on 5% of it.
        return domainWeightPercent >= 20
                && evidence.totalCount() >= MIN_ANSWERS_FOR_WEAKNESS
                && evidence.accuracy() < 0.80;
    }

    /** Ranking score so limited review time goes to what costs the most marks. */
    public static double weaknessScore(Evidence evidence, int domainWeightPercent,
                                       double relevanceFactor) {
        double gap = 1.0 - evidence.accuracy();
        return gap * Math.max(1, domainWeightPercent) * Math.max(0.1, relevanceFactor);
    }

    /**
     * Tier one: a single miss on an essential concept is re-asked tomorrow.
     *
     * <p>Needed because with roughly one question per topic per day, the
     * three-answer threshold above cannot fire during the first week - exactly
     * when a learner most needs the correction.
     */
    public static boolean shouldQueueForWarmup(boolean wasCorrect, String examRelevance) {
        return !wasCorrect && "MUST_KNOW".equals(examRelevance);
    }

    public record Evidence(
            int correctCount,
            int totalCount,
            int sessionsCount,
            LocalDate firstSessionOn,
            LocalDate lastSessionOn,
            boolean unitCompleted) {

        public double accuracy() {
            return totalCount == 0 ? 0 : (double) correctCount / totalCount;
        }
    }
}
