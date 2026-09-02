package com.certcopilot.domain.assessment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.domain.assessment.internal.mastery.MasteryRules;
import com.certcopilot.shared.ExamRelevance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds the topics worth spending scarce review time on.
 *
 * <p>Entirely deterministic. A model can later explain <em>why</em> a learner is
 * struggling with a topic, but whether the topic is weak is arithmetic over
 * answer history, and it has to be reproducible.
 *
 * <p>Ranking matters as much as detection: review time is limited, so it goes to
 * whatever costs the most marks - low accuracy, heavy exam domain, high relevance.
 */
@Service
public class WeakTopicDetector {

    /** Cap on review blocks per replan; more than this crowds out new content. */
    private static final int MAX_REVIEW_BLOCKS = 4;

    private final JdbcTemplate jdbc;

    public WeakTopicDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<WeakTopic> detect(UUID planId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tm.course_topic_id, tm.correct_count, tm.total_count, tm.sessions_count, "
                        + "       tm.first_session_on, tm.last_session_on, tm.status, "
                        + "       ct.title AS topic_title, "
                        + "       COALESCE(MAX(d.weight_percent), 10) AS domain_weight, "
                        + "       COALESCE(MIN(m.relevance), 'MEDIUM') AS relevance, "
                        + "       (SELECT count(*) FROM attempt_question aq "
                        + "          JOIN quiz_attempt a ON a.id = aq.attempt_id "
                        + "          JOIN question q2 ON q2.id = aq.question_id "
                        + "         WHERE a.plan_id = tm.plan_id AND q2.course_topic_id = tm.course_topic_id "
                        + "           AND aq.is_correct = false "
                        + "           AND aq.answered_at > now() - interval '7 days') AS recent_mistakes "
                        + "  FROM topic_mastery tm "
                        + "  JOIN course_topic ct ON ct.id = tm.course_topic_id "
                        + "  LEFT JOIN learning_unit u "
                        + "         ON jsonb_exists(u.course_topic_ids, tm.course_topic_id::text) "
                        + "  LEFT JOIN unit_exam_mapping m ON m.learning_unit_id = u.id "
                        + "  LEFT JOIN task_statement t ON t.id = m.task_statement_id "
                        + "  LEFT JOIN exam_domain d ON d.id = t.exam_domain_id "
                        + " WHERE tm.plan_id = ? AND tm.total_count > 0 "
                        + " GROUP BY tm.plan_id, tm.course_topic_id, tm.correct_count, tm.total_count, "
                        + "          tm.sessions_count, tm.first_session_on, tm.last_session_on, "
                        + "          tm.status, ct.title",
                planId);

        List<WeakTopic> weak = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int total = ((Number) row.get("total_count")).intValue();
            int correct = ((Number) row.get("correct_count")).intValue();
            int sessions = ((Number) row.get("sessions_count")).intValue();
            int domainWeight = ((Number) row.get("domain_weight")).intValue();
            int recentMistakes = ((Number) row.get("recent_mistakes")).intValue();

            LocalDate firstDay = row.get("first_session_on") == null ? null
                    : ((java.sql.Date) row.get("first_session_on")).toLocalDate();
            LocalDate lastDay = row.get("last_session_on") == null ? null
                    : ((java.sql.Date) row.get("last_session_on")).toLocalDate();

            var evidence = new MasteryRules.Evidence(
                    correct, total, sessions, firstDay, lastDay, true);

            if (!MasteryRules.isWeak(evidence, domainWeight, recentMistakes)) {
                continue;
            }

            ExamRelevance relevance = parseRelevance((String) row.get("relevance"));
            double score = MasteryRules.weaknessScore(evidence, domainWeight, relevance.effortWeight());

            weak.add(new WeakTopic(
                    (UUID) row.get("course_topic_id"),
                    (String) row.get("topic_title"),
                    evidence.accuracy(),
                    total,
                    domainWeight,
                    score));
        }

        return weak.stream()
                .sorted(Comparator.comparingDouble(WeakTopic::weaknessScore).reversed())
                .limit(MAX_REVIEW_BLOCKS)
                .toList();
    }

    private static ExamRelevance parseRelevance(String value) {
        try {
            return ExamRelevance.valueOf(value);
        } catch (Exception e) {
            return ExamRelevance.MEDIUM;
        }
    }

    public record WeakTopic(UUID courseTopicId, String title, double accuracy,
                            int answeredCount, int domainWeightPercent, double weaknessScore) {
    }
}
