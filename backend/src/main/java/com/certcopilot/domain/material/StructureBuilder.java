package com.certcopilot.domain.material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.certcopilot.platform.parsing.ParsedDocument;
import com.certcopilot.platform.parsing.ParsedPage;

/**
 * Deterministic structure work that surrounds the model call.
 *
 * <p>Order of preference is deliberate: authored structure beats inferred
 * structure. A PDF outline or a PPTX section header was written by the course
 * author and is simply better information than anything a model can reconstruct
 * from page text.
 *
 * <p>The model is only asked when there is no authored signal, and whatever it
 * returns is validated here before it is allowed to exist.
 */
public final class StructureBuilder {

    private StructureBuilder() {
    }

    /** Sections derived from authored outline entries, if the document has any. */
    public static List<Section> fromOutline(ParsedDocument document) {
        List<ParsedDocument.OutlineEntry> outline = document.outline().stream()
                .filter(e -> e.title() != null && !e.title().isBlank())
                .sorted(Comparator.comparingInt(ParsedDocument.OutlineEntry::pageNo))
                .toList();

        if (outline.size() < 2) {
            return List.of();
        }

        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < outline.size(); i++) {
            ParsedDocument.OutlineEntry entry = outline.get(i);
            int start = entry.pageNo();
            int end = (i + 1 < outline.size() ? outline.get(i + 1).pageNo() - 1 : document.pageCount());
            if (end < start) {
                continue;
            }
            sections.add(new Section(entry.title().strip(), start, end, entry.level()));
        }
        return normalise(sections, document.pageCount());
    }

    /**
     * Last-resort structure when nothing else works: fixed-size chunks named
     * after their first page title.
     *
     * <p>Marked low precision by the caller so the learner is told the structure
     * is approximate and can correct it, rather than being quietly misled.
     */
    public static List<Section> flatFallback(ParsedDocument document, int pagesPerSection) {
        List<Section> sections = new ArrayList<>();
        List<ParsedPage> pages = document.pages();
        for (int start = 1; start <= pages.size(); start += pagesPerSection) {
            int end = Math.min(pages.size(), start + pagesPerSection - 1);
            String title = pages.get(start - 1).titleGuess();
            if (title == null || title.isBlank()) {
                title = "Trang " + start + "–" + end;
            }
            sections.add(new Section(title, start, end, 0));
        }
        return normalise(sections, document.pageCount());
    }

    /**
     * Enforces the material invariant: ranges are ordered, non-overlapping, and
     * cover every page exactly once.
     *
     * <p>Applied to model output as well as to authored outlines, so an invalid
     * structure can never reach the database.
     */
    public static List<Section> normalise(List<Section> input, int pageCount) {
        if (input.isEmpty() || pageCount <= 0) {
            return List.of();
        }

        List<Section> sorted = input.stream()
                .filter(s -> s.pageStart() >= 1 && s.pageStart() <= pageCount)
                .sorted(Comparator.comparingInt(Section::pageStart))
                .toList();
        if (sorted.isEmpty()) {
            return List.of();
        }

        List<Section> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Section current = sorted.get(i);
            int start = current.pageStart();
            // Close each section where the next one begins, so gaps and overlaps
            // both disappear.
            int end = (i + 1 < sorted.size())
                    ? Math.min(pageCount, sorted.get(i + 1).pageStart() - 1)
                    : pageCount;
            if (end < start) {
                continue;
            }
            result.add(new Section(current.title(), start, end, current.level()));
        }

        // The first section always starts at page 1: nothing may be unreachable.
        if (!result.isEmpty() && result.get(0).pageStart() > 1) {
            Section first = result.get(0);
            result.set(0, new Section(first.title(), 1, first.pageEnd(), first.level()));
        }
        return result;
    }

    /** True when the ranges tile the document exactly. Used as a validation gate. */
    public static boolean covers(List<Section> sections, int pageCount) {
        if (sections.isEmpty()) {
            return pageCount == 0;
        }
        List<Section> sorted = sections.stream()
                .sorted(Comparator.comparingInt(Section::pageStart)).toList();
        if (sorted.get(0).pageStart() != 1) {
            return false;
        }
        for (int i = 0; i < sorted.size(); i++) {
            Section s = sorted.get(i);
            if (s.pageEnd() < s.pageStart()) {
                return false;
            }
            if (i + 1 < sorted.size() && sorted.get(i + 1).pageStart() != s.pageEnd() + 1) {
                return false;
            }
        }
        return sorted.get(sorted.size() - 1).pageEnd() == pageCount;
    }

    /**
     * Splits a section into learning units small enough for one sitting, cutting
     * only at page boundaries so a {@code sourceSpan} always addresses whole pages.
     */
    public static List<UnitRange> splitIntoUnits(Section section, List<ParsedPage> pages,
                                                 int maxPagesPerUnit) {
        List<UnitRange> units = new ArrayList<>();
        int span = Math.max(4, maxPagesPerUnit);
        for (int start = section.pageStart(); start <= section.pageEnd(); start += span) {
            int end = Math.min(section.pageEnd(), start + span - 1);
            String title = section.pageEnd() - section.pageStart() + 1 <= span
                    ? section.title()
                    : section.title() + " (" + start + "–" + end + ")";
            units.add(new UnitRange(title, start, end));
        }
        return units;
    }

    public record Section(String title, int pageStart, int pageEnd, int level) {
        public int pageCount() {
            return pageEnd - pageStart + 1;
        }
    }

    public record UnitRange(String title, int pageStart, int pageEnd) {
    }
}
