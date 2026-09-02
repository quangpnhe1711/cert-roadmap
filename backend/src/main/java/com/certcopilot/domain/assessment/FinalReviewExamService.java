package com.certcopilot.domain.assessment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The final review exam, assembled from the validated question bank.
 *
 * <p>Part of the MVP by decision D5. It is deliberately not an exam simulator:
 * the minimum that earns its place is a weighted draw from questions that already
 * passed validation, deterministic grading, and a breakdown by domain. Everything
 * beyond that - timers, section navigation, review flags - is presentation and
 * can wait.
 *
 * <p>Selection is weighted by the official domain distribution, so the result
 * tells the learner something about exam readiness rather than about whichever
 * topics happened to generate the most questions.
 */
@Service
public class FinalReviewExamService {

    private static final Logger log = LoggerFactory.getLogger(FinalReviewExamService.class);

    /** Enough to be informative without becoming a second study session. */
    private static final int DEFAULT_EXAM_SIZE = 30;

    private final JdbcTemplate jdbc;
    private final QuizService quizService;

    public FinalReviewExamService(JdbcTemplate jdbc, QuizService quizService) {
        this.jdbc = jdbc;
        this.quizService = quizService;
    }

    @Transactional(readOnly = true)
    public Readiness readiness(UUID planId) {
        Integer available = jdbc.queryForObject(
                "SELECT count(*) FROM question WHERE plan_id = ? AND validation_status = 'VALID'",
                Integer.class, planId);
        int bank = available == null ? 0 : available;
        // A draw much smaller than the target is not a meaningful exam.
        int minimum = Math.max(10, DEFAULT_EXAM_SIZE / 3);
        return new Readiness(bank, minimum, bank >= minimum);
    }

    /**
     * Draws a weighted paper. Questions are allocated to domains in proportion to
     * their official weight, then filled from the bank.
     */
    @Transactional
    public QuizService.AttemptView start(UUID planId, Integer requestedSize) {
        Readiness readiness = readiness(planId);
        if (!readiness.ready()) {
            throw new QuizService.QuizException("EXAM_BANK_TOO_SMALL",
                    "Ngân hàng câu hỏi chưa đủ (" + readiness.bankSize() + "/"
                            + readiness.minimumRequired() + "). Hãy hoàn thành thêm buổi học.");
        }

        int target = Math.min(
                requestedSize == null ? DEFAULT_EXAM_SIZE : Math.max(10, requestedSize),
                readiness.bankSize());

        List<Map<String, Object>> domains = jdbc.queryForList(
                "SELECT d.id, d.code, d.weight_percent, "
                        + "       (SELECT count(*) FROM question q "
                        + "          JOIN task_statement t2 ON t2.id = q.task_statement_id "
                        + "         WHERE q.plan_id = ? AND q.validation_status = 'VALID' "
                        + "           AND t2.exam_domain_id = d.id) AS available "
                        + "  FROM exam_domain d "
                        + "  JOIN study_plan p ON p.certification_version_id = d.certification_version_id "
                        + " WHERE p.id = ? ORDER BY d.order_index",
                planId, planId);

        List<UUID> selected = new ArrayList<>();
        for (Map<String, Object> domain : domains) {
            int weight = ((Number) domain.get("weight_percent")).intValue();
            int availableInDomain = ((Number) domain.get("available")).intValue();
            int quota = Math.min(availableInDomain, (int) Math.round(target * weight / 100.0));
            if (quota <= 0) {
                continue;
            }
            selected.addAll(jdbc.queryForList(
                    "SELECT q.id FROM question q "
                            + "  JOIN task_statement t ON t.id = q.task_statement_id "
                            + " WHERE q.plan_id = ? AND q.validation_status = 'VALID' "
                            + "   AND t.exam_domain_id = ? ORDER BY random() LIMIT ?",
                    UUID.class, planId, domain.get("id"), quota));
        }

        // Top up from anywhere if rounding or a thin domain left the paper short.
        if (selected.size() < target) {
            List<UUID> filler = selected.isEmpty()
                    ? jdbc.queryForList(
                            "SELECT id FROM question WHERE plan_id = ? AND validation_status = 'VALID' "
                                    + " ORDER BY random() LIMIT ?",
                            UUID.class, planId, target)
                    : jdbc.queryForList(
                            "SELECT id FROM question WHERE plan_id = ? AND validation_status = 'VALID' "
                                    + "   AND id <> ALL (?) ORDER BY random() LIMIT ?",
                            UUID.class, planId, selected.toArray(new UUID[0]), target - selected.size());
            selected.addAll(filler);
        }

        if (selected.isEmpty()) {
            throw new QuizService.QuizException("EXAM_BANK_TOO_SMALL",
                    "Không có câu hỏi hợp lệ nào để tạo đề ôn.");
        }

        log.info("assembled final review exam for plan {}: {} questions", planId, selected.size());
        return createAttempt(planId, selected);
    }

    private QuizService.AttemptView createAttempt(UUID planId, List<UUID> questionIds) {
        UUID attemptId = UUID.randomUUID();
        jdbc.update("INSERT INTO quiz_attempt (id, plan_id, kind, score_total) "
                        + "VALUES (?,?, 'FINAL_REVIEW', ?)",
                attemptId, planId, questionIds.size());
        int index = 0;
        for (UUID questionId : questionIds) {
            jdbc.update("INSERT INTO attempt_question (attempt_id, question_id, order_index) "
                    + "VALUES (?,?,?)", attemptId, questionId, index++);
        }
        return quizService.loadAttempt(planId, attemptId, false);
    }

    /** Result broken down by exam domain, which is what tells a learner where to spend the last days. */
    @Transactional(readOnly = true)
    public ExamResult result(UUID planId, UUID attemptId) {
        Map<String, Object> attempt = jdbc.queryForMap(
                "SELECT score_raw, score_total, status FROM quiz_attempt "
                        + " WHERE id = ? AND plan_id = ?", attemptId, planId);

        List<DomainResult> byDomain = jdbc.query(
                "SELECT d.code, d.title, d.weight_percent, "
                        + "       count(*) AS total, count(*) FILTER (WHERE aq.is_correct) AS correct "
                        + "  FROM attempt_question aq "
                        + "  JOIN question q ON q.id = aq.question_id "
                        + "  JOIN task_statement t ON t.id = q.task_statement_id "
                        + "  JOIN exam_domain d ON d.id = t.exam_domain_id "
                        + " WHERE aq.attempt_id = ? "
                        + " GROUP BY d.code, d.title, d.weight_percent, d.order_index "
                        + " ORDER BY d.order_index",
                (rs, n) -> new DomainResult(
                        rs.getString("code"), rs.getString("title"),
                        rs.getInt("weight_percent"), rs.getInt("correct"), rs.getInt("total")),
                attemptId);

        Integer raw = (Integer) attempt.get("score_raw");
        Integer total = (Integer) attempt.get("score_total");

        return new ExamResult(
                attemptId,
                raw == null ? 0 : raw,
                total == null ? 0 : total,
                (String) attempt.get("status"),
                byDomain,
                weakestDomains(byDomain));
    }

    /** Where the last days of revision should go. */
    private List<String> weakestDomains(List<DomainResult> results) {
        return results.stream()
                .filter(d -> d.total() > 0 && d.accuracy() < 0.7)
                .sorted((a, b) -> Double.compare(
                        (1 - a.accuracy()) * a.weightPercent(),
                        (1 - b.accuracy()) * b.weightPercent()))
                .map(DomainResult::title)
                .toList()
                .reversed();
    }

    public record Readiness(int bankSize, int minimumRequired, boolean ready) {
    }

    public record DomainResult(String code, String title, int weightPercent, int correct, int total) {
        @com.fasterxml.jackson.annotation.JsonIgnore
        public double accuracy() {
            return total == 0 ? 0 : (double) correct / total;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("percent")
        public int percent() {
            return (int) Math.round(accuracy() * 100);
        }
    }

    public record ExamResult(UUID attemptId, int correct, int total, String status,
                             List<DomainResult> byDomain, List<String> focusAreas) {
        @com.fasterxml.jackson.annotation.JsonProperty("percent")
        public int percent() {
            return total == 0 ? 0 : (int) Math.round(100.0 * correct / total);
        }
    }
}
