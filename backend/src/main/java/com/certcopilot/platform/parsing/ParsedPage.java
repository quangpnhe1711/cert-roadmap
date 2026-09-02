package com.certcopilot.platform.parsing;

import com.certcopilot.shared.PageClass;

/**
 * One page or slide as extracted from the source file.
 *
 * <p>Correction P4: {@code pageClass} and {@code hasSignificantVisual} exist so
 * the system never claims an image-heavy slide is fully represented by its
 * extracted text. A Learning Pack built on such a page must send the learner
 * back to the original.
 *
 * @param pageNo             1-based, stable, and what {@code sourceSpan} refers to
 * @param titleGuess         inferred from font size, position or layout role
 * @param text               body text in reading order, bullet hierarchy preserved as indentation
 * @param notesText          speaker notes when the format carries them
 * @param imageAreaRatio     fraction of the page covered by images, 0..1
 * @param extractionQuality  0..1 confidence that the text represents the page
 */
public record ParsedPage(
        int pageNo,
        String titleGuess,
        String text,
        String notesText,
        int wordCount,
        double imageAreaRatio,
        PageClass pageClass,
        boolean hasSignificantVisual,
        double extractionQuality) {
}
