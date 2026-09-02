package com.certcopilot.platform.parsing;

import java.util.List;
import java.util.Map;

/**
 * Result of parsing one uploaded file.
 *
 * @param outline     hard structural signal when the format provides it
 *                    (PDF bookmarks, PPTX section headers). Preferred over any
 *                    inferred structure because it is authored, not guessed.
 * @param qualityFlags counts the UI reports honestly to the learner
 */
public record ParsedDocument(
        List<ParsedPage> pages,
        List<OutlineEntry> outline,
        Map<String, Object> qualityFlags) {

    public int pageCount() {
        return pages.size();
    }

    /** A bookmark or section marker: a title anchored at a page. */
    public record OutlineEntry(String title, int pageNo, int level) {
    }
}
