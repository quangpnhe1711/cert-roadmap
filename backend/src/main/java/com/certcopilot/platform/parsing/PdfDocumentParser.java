package com.certcopilot.platform.parsing;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.certcopilot.shared.PageClass;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PDF extraction, which is the common case because exported slide decks arrive
 * as PDF.
 *
 * <p>Three things beyond plain text matter here:
 *
 * <ul>
 *   <li><b>Title inference</b> - the largest text on a page, taken from font
 *       sizes rather than guessed from position alone.
 *   <li><b>Image area</b> - summed from image XObjects, which is what drives the
 *       page classification. A slide that is 70% diagram is reported as such.
 *   <li><b>Outline</b> - PDF bookmarks are authored structure. When present they
 *       beat anything a model would infer.
 * </ul>
 */
@Component
public class PdfDocumentParser implements DocumentParserPort {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    private static final String VERSION = "pdfbox-3-v1";

    /** Below this word count a page cannot carry a lesson on its own. */
    private static final int SPARSE_TEXT_WORDS = 12;

    @Override
    public boolean supports(String mimeType, String fileName) {
        return "application/pdf".equalsIgnoreCase(mimeType)
                || (fileName != null && fileName.toLowerCase().endsWith(".pdf"));
    }

    @Override
    public String extractionVersion() {
        return VERSION;
    }

    @Override
    public ParsedDocument parse(InputStream input, String fileName) {
        try (PDDocument doc = Loader.loadPDF(input.readAllBytes())) {
            List<ParsedPage> pages = new ArrayList<>();
            int imageDominant = 0;
            int sparse = 0;

            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                TitleAwareStripper stripper = new TitleAwareStripper();
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                stripper.setSortByPosition(true);
                String text = stripper.getText(doc).strip();

                double imageRatio = imageAreaRatio(page);
                int words = countWords(text);
                PageClass pageClass = classify(words, imageRatio);
                boolean significantVisual = imageRatio >= 0.20 || pageClass != PageClass.TEXT_DOMINANT;

                if (pageClass == PageClass.IMAGE_DOMINANT) {
                    imageDominant++;
                }
                if (words < SPARSE_TEXT_WORDS) {
                    sparse++;
                }

                pages.add(new ParsedPage(
                        i + 1,
                        stripper.largestTextLine(),
                        text,
                        null,
                        words,
                        imageRatio,
                        pageClass,
                        significantVisual,
                        extractionQuality(words, imageRatio)));
            }

            Map<String, Object> flags = new LinkedHashMap<>();
            flags.put("pageCount", pages.size());
            flags.put("imageDominantPages", imageDominant);
            flags.put("sparseTextPages", sparse);
            flags.put("hasOutline", !readOutline(doc).isEmpty());

            return new ParsedDocument(pages, readOutline(doc), flags);
        } catch (IOException e) {
            throw new ParsingException("cannot read PDF " + fileName, e);
        }
    }

    /**
     * Page classification (correction P4). The thresholds are deliberately
     * conservative: it is far worse to call an image-heavy slide TEXT_DOMINANT
     * than the reverse, because that is the case where the system would silently
     * teach from content it never read.
     */
    static PageClass classify(int words, double imageRatio) {
        if (imageRatio >= 0.45 || words < SPARSE_TEXT_WORDS) {
            return PageClass.IMAGE_DOMINANT;
        }
        if (imageRatio >= 0.15 || words < 40) {
            return PageClass.MIXED;
        }
        return PageClass.TEXT_DOMINANT;
    }

    static double extractionQuality(int words, double imageRatio) {
        double textScore = Math.min(1.0, words / 60.0);
        return Math.max(0.0, Math.min(1.0, textScore * (1.0 - imageRatio * 0.6)));
    }

    private static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private double imageAreaRatio(PDPage page) {
        try {
            PDResources resources = page.getResources();
            if (resources == null) {
                return 0;
            }
            double pageArea = page.getMediaBox().getWidth() * page.getMediaBox().getHeight();
            if (pageArea <= 0) {
                return 0;
            }
            double imageArea = 0;
            for (var name : resources.getXObjectNames()) {
                PDXObject xObject = resources.getXObject(name);
                if (xObject instanceof PDImageXObject image) {
                    // Intrinsic size is an approximation of placed size; good
                    // enough to separate a decorative icon from a full diagram.
                    imageArea += (double) image.getWidth() * image.getHeight();
                }
            }
            return Math.min(1.0, imageArea / pageArea);
        } catch (Exception e) {
            log.debug("cannot measure image area on a page: {}", e.toString());
            return 0;
        }
    }

    private List<ParsedDocument.OutlineEntry> readOutline(PDDocument doc) {
        List<ParsedDocument.OutlineEntry> entries = new ArrayList<>();
        PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
        if (outline == null) {
            return entries;
        }
        collectOutline(doc, outline.getFirstChild(), 0, entries);
        return entries;
    }

    private void collectOutline(PDDocument doc, PDOutlineItem item, int level,
                                List<ParsedDocument.OutlineEntry> out) {
        while (item != null) {
            try {
                PDPage page = item.findDestinationPage(doc);
                if (page != null) {
                    int pageNo = doc.getPages().indexOf(page) + 1;
                    if (pageNo > 0) {
                        out.add(new ParsedDocument.OutlineEntry(item.getTitle(), pageNo, level));
                    }
                }
            } catch (Exception e) {
                log.debug("skipping unreadable outline entry: {}", e.toString());
            }
            collectOutline(doc, item.getFirstChild(), level + 1, out);
            item = item.getNextSibling();
        }
    }

    /** Captures the visually largest line, which is a slide title far more often than not. */
    private static class TitleAwareStripper extends PDFTextStripper {

        private double largestSize = 0;
        private String largestLine = null;

        TitleAwareStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (!positions.isEmpty() && !text.isBlank()) {
                double avg = positions.stream()
                        .mapToDouble(TextPosition::getFontSizeInPt)
                        .average().orElse(0);
                if (avg > largestSize && text.strip().length() > 2) {
                    largestSize = avg;
                    largestLine = text.strip();
                }
            }
            super.writeString(text, positions);
        }

        String largestTextLine() {
            if (largestLine == null) {
                return null;
            }
            return largestLine.length() > 200 ? largestLine.substring(0, 200) : largestLine;
        }
    }

    public static class ParsingException extends RuntimeException {
        public ParsingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
