package com.certcopilot.platform.parsing;

import java.io.ByteArrayInputStream;

import com.certcopilot.shared.PageClass;
import com.certcopilot.support.SyntheticDeck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correction P4, made executable.
 *
 * <p>The correction exists because "percentage of pages containing text" is a
 * useless quality metric: a slide with a title and one diagram has text, and
 * teaching from that text alone means teaching from a caption. The classifier
 * has to notice, and {@code hasSignificantVisual} has to survive all the way to
 * the learner so the lesson can send them back to the slide.
 *
 * <p>The boundaries are asserted directly, because they are the part that drifts.
 */
class PageClassificationTest {

    @Test
    @DisplayName("dense prose with no images is text-dominant")
    void denseTextIsTextDominant() {
        assertThat(PdfDocumentParser.classify(120, 0.0)).isEqualTo(PageClass.TEXT_DOMINANT);
        assertThat(PdfDocumentParser.classify(41, 0.14)).isEqualTo(PageClass.TEXT_DOMINANT);
    }

    @Test
    @DisplayName("a page that is mostly picture is image-dominant whatever text it carries")
    void largeImagesDominate() {
        assertThat(PdfDocumentParser.classify(500, 0.45)).isEqualTo(PageClass.IMAGE_DOMINANT);
        assertThat(PdfDocumentParser.classify(500, 0.90)).isEqualTo(PageClass.IMAGE_DOMINANT);
    }

    @Test
    @DisplayName("a page with almost no words is image-dominant even with no detected images")
    void sparseTextIsImageDominant() {
        // The diagram-only slide: PDFBox reports no image object because the
        // diagram is vector art, and the only text is a four-word title. Calling
        // that text-dominant is exactly the mistake P4 was written to prevent.
        assertThat(PdfDocumentParser.classify(4, 0.0)).isEqualTo(PageClass.IMAGE_DOMINANT);
        assertThat(PdfDocumentParser.classify(11, 0.0)).isEqualTo(PageClass.IMAGE_DOMINANT);
    }

    @Test
    @DisplayName("the middle ground is mixed, not silently rounded to either side")
    void middleGroundIsMixed() {
        assertThat(PdfDocumentParser.classify(200, 0.15)).isEqualTo(PageClass.MIXED);
        assertThat(PdfDocumentParser.classify(200, 0.44)).isEqualTo(PageClass.MIXED);
        assertThat(PdfDocumentParser.classify(39, 0.0)).isEqualTo(PageClass.MIXED);
    }

    @Test
    @DisplayName("extraction quality falls as text thins and images grow")
    void extractionQualityTracksRecoverability() {
        double dense = PdfDocumentParser.extractionQuality(120, 0.0);
        double thin = PdfDocumentParser.extractionQuality(10, 0.0);
        double imageHeavy = PdfDocumentParser.extractionQuality(120, 0.8);

        assertThat(dense).isEqualTo(1.0);
        assertThat(thin).isLessThan(dense);
        assertThat(imageHeavy).isLessThan(dense);
        assertThat(PdfDocumentParser.extractionQuality(0, 1.0)).isZero();
    }

    @Test
    @DisplayName("a parsed deck carries page identity, classification and the visual flag")
    void parsingPreservesTheSignals() throws Exception {
        byte[] deck = SyntheticDeck.build(3);

        ParsedDocument parsed = new PdfDocumentParser()
                .parse(new ByteArrayInputStream(deck), "deck.pdf");

        assertThat(parsed.pages()).isNotEmpty();

        // Page identity is what every sourceSpan resolves against; if it is not
        // 1-based and contiguous, every citation in the product points somewhere else.
        for (int i = 0; i < parsed.pages().size(); i++) {
            assertThat(parsed.pages().get(i).pageNo()).isEqualTo(i + 1);
        }

        assertThat(parsed.pages())
                .as("a deck with diagram slides must produce image-dominant pages")
                .anyMatch(page -> page.pageClass() == PageClass.IMAGE_DOMINANT);

        assertThat(parsed.pages())
                .filteredOn(page -> page.pageClass() != PageClass.TEXT_DOMINANT)
                .as("anything not text-dominant must be flagged so the lesson sends the "
                        + "learner back to the slide")
                .allMatch(ParsedPage::hasSignificantVisual);

        assertThat(parsed.qualityFlags())
                .containsKey("imageDominantPages")
                .containsKey("pageCount");
    }

    @Test
    @DisplayName("parsing the same bytes twice yields the same pages")
    void parsingIsDeterministic() throws Exception {
        byte[] deck = SyntheticDeck.build(2);
        PdfDocumentParser parser = new PdfDocumentParser();

        ParsedDocument first = parser.parse(new ByteArrayInputStream(deck), "deck.pdf");
        ParsedDocument second = parser.parse(new ByteArrayInputStream(deck), "deck.pdf");

        // Page numbers are baked into every generated artifact's identity; an
        // unstable parse would silently invalidate the whole cache on reprocess.
        assertThat(first.pages()).isEqualTo(second.pages());
    }
}
