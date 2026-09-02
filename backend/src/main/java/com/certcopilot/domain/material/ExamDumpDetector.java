package com.certcopilot.domain.material;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Refuses leaked exam material at the door.
 *
 * <p>This is a product boundary, not a nicety: the certification study market is
 * full of "braindumps", and a system that ingests them becomes a laundering
 * service for them. Rejecting at upload is cheap and means the content never
 * reaches generation, storage or another learner.
 *
 * <p>Heuristic, so it is tuned to avoid false positives on legitimate practice
 * material: a course deck with a few sample questions passes, a file that is
 * mostly numbered questions with lettered answers and no teaching content does
 * not.
 */
@Component
public class ExamDumpDetector {

    /** Filenames that state the intent outright. */
    private static final List<Pattern> FILENAME_SIGNALS = List.of(
            Pattern.compile("brain\\s*dump", Pattern.CASE_INSENSITIVE),
            Pattern.compile("exam\\s*dump", Pattern.CASE_INSENSITIVE),
            Pattern.compile("real\\s*exam\\s*(questions?|q\\s*&?\\s*a)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("actual\\s*exam\\s*questions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdumps?\\b.*\\b(20\\d{2})\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("leaked", Pattern.CASE_INSENSITIVE));

    /** Phrases that claim the content is the real exam. */
    private static final List<Pattern> CONTENT_SIGNALS = List.of(
            Pattern.compile("actual exam questions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("real exam dump", Pattern.CASE_INSENSITIVE),
            Pattern.compile("verified answers from the real exam", Pattern.CASE_INSENSITIVE),
            Pattern.compile("questions? (?:appeared|came) (?:in|on) the (?:real|actual) exam",
                    Pattern.CASE_INSENSITIVE));

    private static final Pattern NUMBERED_QUESTION =
            Pattern.compile("(?m)^\\s*(?:question\\s*)?\\d{1,3}\\s*[.):]");
    private static final Pattern LETTERED_OPTION =
            Pattern.compile("(?m)^\\s*[A-E]\\s*[.)]\\s+\\S");
    private static final Pattern CORRECT_ANSWER_MARKER =
            Pattern.compile("correct answer\\s*[:=]", Pattern.CASE_INSENSITIVE);

    public Verdict inspect(String fileName, byte[] content) {
        if (fileName != null) {
            for (Pattern pattern : FILENAME_SIGNALS) {
                if (pattern.matcher(fileName).find()) {
                    return Verdict.reject(
                            "Tên tệp cho thấy đây là đề thi bị rò rỉ. Sản phẩm này không nhận exam dump.");
                }
            }
        }

        // Only PDFs expose readable text without full parsing; PPTX is a ZIP, so
        // the deeper check happens after extraction.
        String sample = sampleText(content);
        if (sample.isBlank()) {
            return Verdict.accept();
        }

        for (Pattern pattern : CONTENT_SIGNALS) {
            if (pattern.matcher(sample).find()) {
                return Verdict.reject(
                        "Nội dung tệp tuyên bố là câu hỏi thi thật. Sản phẩm này không nhận exam dump.");
            }
        }

        return Verdict.accept();
    }

    /**
     * Structural check applied to extracted text once a document is parsed.
     *
     * <p>A file dominated by numbered questions, lettered options and explicit
     * answer keys, with very little prose, is a dump regardless of what it is
     * called.
     */
    public Verdict inspectExtractedText(String fullText) {
        if (fullText == null || fullText.length() < 500) {
            return Verdict.accept();
        }
        String lower = fullText.toLowerCase(Locale.ROOT);

        for (Pattern pattern : CONTENT_SIGNALS) {
            if (pattern.matcher(lower).find()) {
                return Verdict.reject(
                        "Nội dung tệp tuyên bố là câu hỏi thi thật. Sản phẩm này không nhận exam dump.");
            }
        }

        long questions = NUMBERED_QUESTION.matcher(fullText).results().count();
        long options = LETTERED_OPTION.matcher(fullText).results().count();
        long answerKeys = CORRECT_ANSWER_MARKER.matcher(fullText).results().count();
        long words = fullText.split("\\s+").length;

        // A teaching deck with sample questions has plenty of prose per question.
        // A dump is almost entirely question blocks.
        boolean questionDominated = questions >= 25 && options >= questions * 2
                && answerKeys >= questions * 0.6
                && words / Math.max(1, questions) < 90;

        if (questionDominated) {
            return Verdict.reject(
                    "Tệp gần như chỉ gồm câu hỏi và đáp án, không có nội dung giảng dạy. "
                            + "Sản phẩm này không nhận exam dump.");
        }
        return Verdict.accept();
    }

    private static String sampleText(byte[] content) {
        int limit = Math.min(content.length, 64 * 1024);
        String raw = new String(content, 0, limit, StandardCharsets.ISO_8859_1);
        // Strip binary noise so pattern matching sees readable fragments only.
        return raw.replaceAll("[^\\x20-\\x7E\\n]", " ");
    }

    public record Verdict(boolean rejected, String reason) {
        static Verdict accept() {
            return new Verdict(false, null);
        }

        static Verdict reject(String reason) {
            return new Verdict(true, reason);
        }
    }
}
