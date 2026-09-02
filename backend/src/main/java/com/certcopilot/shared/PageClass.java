package com.certcopilot.shared;

/**
 * Correction P4: a slide deck legitimately contains diagrams and screenshots.
 * Classifying pages is what stops the system treating an image-heavy slide as
 * fully represented by whatever text happened to be extractable.
 */
public enum PageClass {
    TEXT_DOMINANT,
    MIXED,
    IMAGE_DOMINANT
}
