package com.certcopilot.platform.parsing;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.certcopilot.shared.PageClass;
import org.apache.poi.sl.usermodel.PlaceholderDetails;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PPTX extraction, which is the best case: the format carries real structure.
 *
 * <p>Three things PDF cannot give us and this parser keeps:
 *
 * <ul>
 *   <li><b>Title placeholders</b> - an authored title, not a font-size guess.
 *   <li><b>Bullet indent levels</b> - hierarchy preserved as indentation, so a
 *       sub-point stays subordinate in the generated lesson.
 *   <li><b>Speaker notes</b> - frequently the missing narration that a slide
 *       alone does not carry, which is exactly what a reader of the deck lacks.
 * </ul>
 */
@Component
public class PptxDocumentParser implements DocumentParserPort {

    private static final Logger log = LoggerFactory.getLogger(PptxDocumentParser.class);

    private static final String VERSION = "poi-5-v1";
    private static final String PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    @Override
    public boolean supports(String mimeType, String fileName) {
        return PPTX_MIME.equalsIgnoreCase(mimeType)
                || (fileName != null && fileName.toLowerCase().endsWith(".pptx"));
    }

    @Override
    public String extractionVersion() {
        return VERSION;
    }

    @Override
    public ParsedDocument parse(InputStream input, String fileName) {
        try (XMLSlideShow show = new XMLSlideShow(input)) {
            double slideArea = show.getPageSize().getWidth() * show.getPageSize().getHeight();
            List<ParsedPage> pages = new ArrayList<>();
            List<ParsedDocument.OutlineEntry> outline = new ArrayList<>();
            int imageDominant = 0;
            int withNotes = 0;

            List<XSLFSlide> slides = show.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                SlideContent content = readSlide(slide, slideArea);

                if (content.title != null && !content.title.isBlank()) {
                    // A slide whose only content is a title reads as a section
                    // divider; that is authored structure worth keeping.
                    boolean sectionLike = content.bodyWordCount < 8;
                    outline.add(new ParsedDocument.OutlineEntry(
                            content.title, i + 1, sectionLike ? 0 : 1));
                }

                PageClass pageClass = PdfDocumentParser.classify(content.wordCount, content.imageRatio);
                boolean significantVisual = content.imageRatio >= 0.20
                        || pageClass != PageClass.TEXT_DOMINANT;
                if (pageClass == PageClass.IMAGE_DOMINANT) {
                    imageDominant++;
                }
                if (content.notes != null && !content.notes.isBlank()) {
                    withNotes++;
                }

                pages.add(new ParsedPage(
                        i + 1,
                        content.title,
                        content.text,
                        content.notes,
                        content.wordCount,
                        content.imageRatio,
                        pageClass,
                        significantVisual,
                        PdfDocumentParser.extractionQuality(content.wordCount, content.imageRatio)));
            }

            Map<String, Object> flags = new LinkedHashMap<>();
            flags.put("pageCount", pages.size());
            flags.put("imageDominantPages", imageDominant);
            flags.put("slidesWithSpeakerNotes", withNotes);
            flags.put("hasOutline", !outline.isEmpty());
            // D1 says the learning pack is a companion, which is only true if the
            // learner can see the original page next to it. There is no PPTX
            // rendition pipeline, so this format cannot honour that yet and says so
            // rather than letting the learner discover it mid-lesson.
            flags.put("originalViewUnavailable", true);

            return new ParsedDocument(pages, outline, flags);
        } catch (IOException e) {
            throw new PdfDocumentParser.ParsingException("cannot read PPTX " + fileName, e);
        }
    }

    private SlideContent readSlide(XSLFSlide slide, double slideArea) {
        StringBuilder body = new StringBuilder();
        String title = null;
        double imageArea = 0;
        int bodyWords = 0;

        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFPictureShape picture) {
                var anchor = picture.getAnchor();
                imageArea += anchor.getWidth() * anchor.getHeight();
            } else if (shape instanceof XSLFTable table) {
                body.append(renderTable(table));
            } else if (shape instanceof XSLFTextShape textShape) {
                String text = textShape.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (title == null && isTitlePlaceholder(textShape)) {
                    title = text.strip();
                    continue;
                }
                String rendered = renderParagraphs(textShape);
                bodyWords += countWords(rendered);
                body.append(rendered);
            }
        }

        // Fall back to the first line when the deck does not use placeholders.
        if (title == null && !body.isEmpty()) {
            String first = body.toString().lines().findFirst().orElse("").strip();
            if (!first.isBlank() && first.length() <= 120) {
                title = first;
            }
        }

        String notes = readNotes(slide);
        String text = body.toString().strip();
        int words = countWords(text);
        double ratio = slideArea > 0 ? Math.min(1.0, imageArea / slideArea) : 0;

        return new SlideContent(title, text, notes, words, bodyWords, ratio);
    }

    /** Bullet hierarchy survives as indentation, so nesting is not lost. */
    private String renderParagraphs(XSLFTextShape shape) {
        StringBuilder sb = new StringBuilder();
        for (XSLFTextParagraph paragraph : shape.getTextParagraphs()) {
            String text = paragraph.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append("  ".repeat(Math.max(0, paragraph.getIndentLevel())))
              .append(text.strip())
              .append('\n');
        }
        return sb.toString();
    }

    private String renderTable(XSLFTable table) {
        StringBuilder sb = new StringBuilder();
        for (XSLFTableRow row : table.getRows()) {
            List<String> cells = row.getCells().stream()
                    .map(cell -> cell.getText() == null ? "" : cell.getText().strip())
                    .toList();
            sb.append(String.join(" | ", cells)).append('\n');
        }
        return sb.toString();
    }

    private boolean isTitlePlaceholder(XSLFTextShape shape) {
        try {
            PlaceholderDetails details = shape.getPlaceholderDetails();
            if (details == null || details.getPlaceholder() == null) {
                return false;
            }
            String name = details.getPlaceholder().name();
            return name.contains("TITLE") || name.contains("CENTERED_TITLE");
        } catch (Exception e) {
            return false;
        }
    }

    private String readNotes(XSLFSlide slide) {
        try {
            XSLFNotes notes = slide.getNotes();
            if (notes == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (var shape : notes.getShapes()) {
                if (shape instanceof XSLFTextShape textShape && textShape.getText() != null) {
                    sb.append(textShape.getText().strip()).append('\n');
                }
            }
            String result = sb.toString().strip();
            return result.isBlank() ? null : result;
        } catch (Exception e) {
            log.debug("cannot read speaker notes: {}", e.toString());
            return null;
        }
    }

    private static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private record SlideContent(
            String title, String text, String notes,
            int wordCount, int bodyWordCount, double imageRatio) {
    }
}
