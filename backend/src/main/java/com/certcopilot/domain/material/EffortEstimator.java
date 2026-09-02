package com.certcopilot.domain.material;

import java.util.List;

import com.certcopilot.platform.parsing.ParsedPage;
import com.certcopilot.shared.PageClass;

/**
 * Turns real material volume into minutes. Deterministic, and the reason a plan
 * reflects the learner's actual deck rather than a template.
 *
 * <p>Two design points worth keeping:
 *
 * <ul>
 *   <li>A model may only contribute a difficulty tier, and its influence is
 *       clamped. Everything else is counted from the file.
 *   <li>The total is normalised against available capacity by the caller.
 *       Without that step an estimate is either wildly high or wildly low, and
 *       the resulting schedule is fiction.
 * </ul>
 */
public final class EffortEstimator {

    /** Reading and absorbing a dense text page. */
    private static final double MINUTES_PER_TEXT_PAGE = 2.6;

    /** A diagram-heavy page takes longer per word but has fewer words. */
    private static final double MINUTES_PER_MIXED_PAGE = 2.0;
    private static final double MINUTES_PER_IMAGE_PAGE = 1.4;

    /** Words a learner absorbs per minute in a second language, conservatively. */
    private static final double WORDS_PER_MINUTE = 90.0;

    /** Fixed per-unit overhead: orientation, note-taking, the unit's own check. */
    private static final int UNIT_OVERHEAD_MINUTES = 6;

    private EffortEstimator() {
    }

    /**
     * Base effort for a page range. Certification-agnostic by design (A1): domain
     * weight is applied later, at plan level.
     *
     * @param difficultyTier 1..5, supplied by the model, clamped to +/-40%
     */
    public static int baseEffortMinutes(List<ParsedPage> pages, int difficultyTier) {
        if (pages.isEmpty()) {
            return UNIT_OVERHEAD_MINUTES;
        }

        double pageMinutes = 0;
        int words = 0;
        for (ParsedPage page : pages) {
            pageMinutes += switch (page.pageClass()) {
                case TEXT_DOMINANT -> MINUTES_PER_TEXT_PAGE;
                case MIXED -> MINUTES_PER_MIXED_PAGE;
                case IMAGE_DOMINANT -> MINUTES_PER_IMAGE_PAGE;
            };
            words += page.wordCount();
        }

        double readingMinutes = words / WORDS_PER_MINUTE;
        // Take the larger of the two signals: a wordy page and a slide-count-heavy
        // section are both real, and underestimating is what makes plans slip.
        double base = Math.max(pageMinutes, readingMinutes);

        double difficultyMultiplier = clampDifficulty(difficultyTier);
        int estimate = (int) Math.round(base * difficultyMultiplier) + UNIT_OVERHEAD_MINUTES;

        return Math.max(UNIT_OVERHEAD_MINUTES + 4, estimate);
    }

    /** Tier 1..5 maps to 0.8 .. 1.4, so a model can shift effort but not dominate it. */
    static double clampDifficulty(int tier) {
        int bounded = Math.max(1, Math.min(5, tier));
        return 0.8 + (bounded - 1) * 0.15;
    }

    /**
     * Scales every unit so the total matches what the learner can actually do.
     *
     * <p>This step is what keeps the estimate honest. Absolute per-page timings
     * differ hugely between learners and decks; the ratio between units is the
     * part the model is good at.
     *
     * @return scaling factor applied
     */
    public static double normalisationFactor(int totalEstimatedMinutes, int schedulableMinutes) {
        if (totalEstimatedMinutes <= 0 || schedulableMinutes <= 0) {
            return 1.0;
        }
        double factor = (double) schedulableMinutes / totalEstimatedMinutes;
        // Never distort by more than 2x in either direction: beyond that the
        // mismatch is real and the learner should be told, not hidden from.
        return Math.max(0.5, Math.min(2.0, factor));
    }

    public static int applyNormalisation(int baseMinutes, double factor) {
        return Math.max(UNIT_OVERHEAD_MINUTES + 4, (int) Math.round(baseMinutes * factor));
    }

    /** Splits a page range into unit-sized chunks that never exceed one sitting. */
    public static int suggestedUnitPageSpan(PageClass dominantClass) {
        return switch (dominantClass) {
            case TEXT_DOMINANT -> 18;
            case MIXED -> 24;
            case IMAGE_DOMINANT -> 30;
        };
    }
}
