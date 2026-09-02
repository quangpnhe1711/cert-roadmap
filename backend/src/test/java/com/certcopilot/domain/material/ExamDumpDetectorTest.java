package com.certcopilot.domain.material;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The product boundary that keeps this from becoming a braindump laundry.
 *
 * <p>Both directions matter and pull against each other. Missing a dump means
 * ingesting leaked exam content, generating from it and serving it back. Being
 * too eager means rejecting a legitimate course deck that happens to include
 * practice questions, which is most good course decks - and a learner whose real
 * material is refused has no way to use the product at all.
 */
class ExamDumpDetectorTest {

    private final ExamDumpDetector detector = new ExamDumpDetector();

    @Test
    @DisplayName("a filename that states the intent is refused")
    void filenameSignalsAreRefused() {
        assertThat(detector.inspect("AIF-C01 braindump.pdf", pdf("nội dung")).rejected()).isTrue();
        assertThat(detector.inspect("real exam questions.pdf", pdf("nội dung")).rejected()).isTrue();
        assertThat(detector.inspect("aws dumps 2026.pdf", pdf("nội dung")).rejected()).isTrue();
        assertThat(detector.inspect("leaked-aif.pdf", pdf("nội dung")).rejected()).isTrue();
    }

    @Test
    @DisplayName("an ordinary course filename is accepted")
    void ordinaryFilenamesPass() {
        assertThat(detector.inspect("AIF-C01 Course Slides.pdf", pdf("nội dung")).rejected())
                .isFalse();
        assertThat(detector.inspect("bai-giang-tuan-1.pdf", pdf("nội dung")).rejected()).isFalse();
        assertThat(detector.inspect(null, pdf("nội dung")).rejected()).isFalse();
    }

    @Test
    @DisplayName("content claiming to be the real exam is refused whatever the file is called")
    void contentSignalsAreRefused() {
        assertThat(detector.inspect("course.pdf",
                pdf("These are the actual exam questions with verified answers")).rejected())
                .isTrue();
    }

    @Test
    @DisplayName("a file that is nothing but numbered questions and answer keys is refused")
    void questionDominatedFilesAreRefused() {
        StringBuilder dump = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            dump.append(i).append(". Which AWS service provides foundation models?\n")
                .append("A) Amazon Bedrock\n")
                .append("B) Amazon SageMaker\n")
                .append("C) Amazon Comprehend\n")
                .append("Correct Answer: A\n\n");
        }

        assertThat(detector.inspectExtractedText(dump.toString()).rejected())
                .as("no teaching content at all, whatever the file is called")
                .isTrue();
    }

    @Test
    @DisplayName("a teaching deck with practice questions is accepted")
    void teachingDecksWithPracticeQuestionsPass() {
        // The false positive that would matter most: this is what a good course
        // deck looks like, and refusing it makes the product unusable.
        StringBuilder deck = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            deck.append("Chương ").append(i).append(": Dịch vụ AI trên AWS\n")
                .append("Amazon Bedrock là dịch vụ được quản trị hoàn toàn cung cấp quyền truy cập ")
                .append("vào các foundation model thông qua một API duy nhất. Người học cần phân biệt ")
                .append("Bedrock với SageMaker: Bedrock dành cho mô hình có sẵn, SageMaker dành cho ")
                .append("việc huấn luyện mô hình tùy chỉnh trên dữ liệu của bạn. Trong kỳ thi, câu hỏi ")
                .append("thường xoay quanh việc chọn dịch vụ phù hợp với tình huống nghiệp vụ.\n")
                .append(i).append(". Câu hỏi luyện tập: Dịch vụ nào phù hợp?\n")
                .append("A) Amazon Bedrock\nB) Amazon SageMaker\n")
                .append("Correct answer: A\n\n");
        }

        assertThat(detector.inspectExtractedText(deck.toString()).rejected())
                .as("plenty of prose per question is what separates a course from a dump")
                .isFalse();
    }

    @Test
    @DisplayName("a short document is not judged on structure alone")
    void shortDocumentsAreNotJudged() {
        assertThat(detector.inspectExtractedText("1. Câu hỏi\nA) Đáp án\nCorrect answer: A")
                .rejected())
                .as("too little text to conclude anything, and guessing would block real uploads")
                .isFalse();
        assertThat(detector.inspectExtractedText(null).rejected()).isFalse();
    }

    @Test
    @DisplayName("a rejection explains itself in the learner's language")
    void rejectionsAreExplained() {
        ExamDumpDetector.Verdict verdict = detector.inspect("braindump.pdf", pdf("x"));

        assertThat(verdict.reason())
                .isNotBlank()
                .contains("exam dump");
    }

    private static byte[] pdf(String text) {
        return ("%PDF-1.4\n" + text).getBytes(StandardCharsets.UTF_8);
    }
}
