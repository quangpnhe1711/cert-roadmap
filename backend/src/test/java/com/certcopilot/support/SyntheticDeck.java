package com.certcopilot.support;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Builds a synthetic AIF-C01-shaped slide deck for tests.
 *
 * <p>Real course material cannot be committed to a repository, and using it in
 * CI would be both a licensing problem and a source of flakiness. This generator
 * produces a deck with the properties that actually matter to the pipeline:
 * section-title slides, dense body slides, and diagram-heavy slides with almost
 * no text so page classification and the "significant visual" flag are exercised.
 *
 * <p>It is a fixture for the pipeline, not a substitute for the real-material
 * spike: content quality can only be judged against a real deck.
 */
public final class SyntheticDeck {

    private static final PDType1Font TITLE_FONT =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font BODY_FONT =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    /** Sections mirroring the shape of a real practitioner-level course. */
    private static final List<Section> SECTIONS = List.of(
            new Section("AI and Machine Learning Fundamentals", List.of(
                    "Artificial Intelligence is the broad field of building systems that perform tasks requiring human intelligence.",
                    "Machine Learning is a subset of AI where models learn patterns from data instead of following explicit rules.",
                    "Deep Learning uses multi layer neural networks and underpins most modern Foundation Models.",
                    "Training is the process of fitting a model to data. Inference is using the trained model to make a prediction.",
                    "Supervised Learning uses labelled data. Unsupervised Learning finds structure in unlabelled data.",
                    "Reinforcement Learning trains an agent through rewards received from an environment.",
                    "Overfitting means the model memorised the training data and generalises poorly to new data.",
                    "Underfitting means the model is too simple to capture the underlying pattern in the data.")),
            new Section("Generative AI Fundamentals", List.of(
                    "A Foundation Model is a large model pre trained on broad data and adaptable to many downstream tasks.",
                    "A Large Language Model is a Foundation Model specialised for understanding and generating text.",
                    "A Token is the unit of text a model processes. Prices and limits are usually expressed in tokens.",
                    "An Embedding is a numeric vector representation of text that captures semantic similarity.",
                    "The Context Window is the maximum number of tokens a model can consider in a single request.",
                    "Temperature controls randomness. Lower temperature gives more deterministic and repeatable output.",
                    "Top P sampling restricts the model to the smallest set of tokens whose probabilities sum to P.",
                    "Hallucination is when a model produces confident output that is not grounded in any real source.")),
            new Section("Applications of Foundation Models", List.of(
                    "Retrieval Augmented Generation supplies a model with relevant documents retrieved at request time.",
                    "RAG suits frequently changing knowledge because the source data can be updated without retraining.",
                    "Fine tuning adjusts model weights on task specific data and is better for changing style or format.",
                    "A Vector Store holds embeddings and supports similarity search over them.",
                    "Prompt Engineering is designing the instruction so the model produces the required output.",
                    "Zero shot prompting gives no examples. Few shot prompting includes a small number of examples.",
                    "Chain of Thought prompting asks the model to reason step by step before answering.",
                    "Model evaluation combines automated benchmarks with human review of representative outputs.")),
            new Section("Responsible AI", List.of(
                    "Bias in a model usually originates in the data it was trained on rather than in the algorithm.",
                    "Fairness requires checking that model performance is comparable across relevant groups.",
                    "Transparency means being clear about where a model is used and what its limitations are.",
                    "Explainability is the ability to describe why a model produced a particular output.",
                    "Guardrails constrain model behaviour by filtering inputs and outputs against defined policies.",
                    "Human oversight remains necessary for decisions with significant consequences.")),
            new Section("Security, Compliance and Governance", List.of(
                    "Least privilege means granting only the permissions a workload actually requires.",
                    "Data used with a model must be protected in transit and at rest through encryption.",
                    "Prompt injection is an attack where untrusted input changes the instructions a model follows.",
                    "Auditing and traceability let an organisation show how an AI decision was produced.",
                    "Regulated industries impose additional record keeping and review obligations on AI systems.")));

    private SyntheticDeck() {
    }

    /** @return PDF bytes for a deck with the given number of extra diagram-only slides */
    public static byte[] build(int diagramSlides) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Section section : SECTIONS) {
                addTitleSlide(doc, section.title());
                for (String line : section.bullets()) {
                    addContentSlide(doc, section.title(), line);
                }
                // A slide that is mostly a diagram: little text, large image area.
                for (int i = 0; i < diagramSlides; i++) {
                    addDiagramSlide(doc, section.title() + " overview");
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    public static byte[] build() throws IOException {
        return build(1);
    }

    /**
     * A deck large enough that a release-sized sample of learning units is
     * structurally possible. Sections are repeated with distinct titles so unit
     * splitting produces many units rather than a handful of huge ones.
     */
    public static byte[] buildLarge(int passes) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int pass = 1; pass <= passes; pass++) {
                for (Section section : SECTIONS) {
                    String title = passes == 1 ? section.title()
                            : section.title() + " - Part " + pass;
                    addTitleSlide(doc, title);
                    for (String line : section.bullets()) {
                        addContentSlide(doc, title, line);
                    }
                    addDiagramSlide(doc, title + " overview");
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void addTitleSlide(PDDocument doc, String title) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(TITLE_FONT, 30);
            cs.newLineAtOffset(60, 420);
            cs.showText(title);
            cs.endText();
        }
    }

    private static void addContentSlide(PDDocument doc, String sectionTitle, String body)
            throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(TITLE_FONT, 22);
            cs.newLineAtOffset(50, 700);
            cs.showText(sectionTitle);
            cs.endText();

            cs.beginText();
            cs.setFont(BODY_FONT, 13);
            cs.newLineAtOffset(50, 640);
            cs.setLeading(20);
            for (String line : wrap(body, 70)) {
                cs.showText(line);
                cs.newLine();
            }
            // Extra prose so the page is genuinely text-dominant and a
            // sourceSpan has real sentences to anchor to.
            cs.newLine();
            for (String line : wrap("This is important for the certification exam. "
                    + "You should be able to recognise the concept in a scenario question "
                    + "and distinguish it from adjacent concepts covered in this section.", 70)) {
                cs.showText(line);
                cs.newLine();
            }
            cs.endText();
        }
    }

    /** Large filled rectangles standing in for a diagram, with a bare caption. */
    private static void addDiagramSlide(PDDocument doc, String caption) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(new Color(220, 230, 235));
            cs.addRect(50, 200, 500, 450);
            cs.fill();

            cs.setNonStrokingColor(Color.BLACK);
            cs.beginText();
            cs.setFont(BODY_FONT, 11);
            cs.newLineAtOffset(50, 150);
            cs.showText(caption);
            cs.endText();
        }
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() + word.length() + 1 > width) {
                lines.add(current.toString());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private record Section(String title, List<String> bullets) {
    }
}
