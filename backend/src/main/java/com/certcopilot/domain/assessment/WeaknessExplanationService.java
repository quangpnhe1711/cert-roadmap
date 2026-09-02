package com.certcopilot.domain.assessment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.certcopilot.platform.ai.AiGateway;
import com.certcopilot.platform.ai.AiOperation;
import com.certcopilot.platform.ai.AiOperationRegistry;
import com.certcopilot.platform.ai.AiOperations;
import com.certcopilot.platform.ai.AiResult;
import com.certcopilot.platform.ai.ArtifactKey;
import com.certcopilot.shared.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short review note attached to a weak topic.
 *
 * <p>{@link WeakTopicDetector} decides <em>that</em> a topic is weak; nothing here
 * may change that. This explains <em>why</em> the learner keeps getting it wrong,
 * from the questions they actually missed, and the validator rejects any output
 * that tries to emit a score or a mastery status - scoring is arithmetic and
 * stays arithmetic.
 *
 * <p>Declared non-essential in {@link AiOperations}: at the hard budget cap this
 * is refused before a lesson is, because a missing explanation is an
 * inconvenience and a missing lesson is a broken day.
 */
@Service
public class WeaknessExplanationService {

    private static final Logger log = LoggerFactory.getLogger(WeaknessExplanationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Enough missed questions to see the pattern, few enough to keep the prompt cheap. */
    private static final int MAX_MISSED_QUESTIONS = 5;

    private static final int MAX_WORDS = 180;

    private final JdbcTemplate jdbc;
    private final AiGateway gateway;
    private final AiOperationRegistry operations;
    private final com.certcopilot.platform.ai.PromptRegistry prompts;

    public WeaknessExplanationService(JdbcTemplate jdbc,
                                      AiGateway gateway,
                                      AiOperationRegistry operations,
                                      com.certcopilot.platform.ai.PromptRegistry prompts) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.operations = operations;
        this.prompts = prompts;
    }

    /**
     * Explains one weak topic, generating the note on first request.
     *
     * <p>Empty when there is no answer history for the topic yet, or when the
     * operation was refused - the caller shows the deterministic weak-topic row
     * without a note rather than blocking on it.
     */
    @Transactional
    public Optional<Explanation> explain(UUID planId, UUID courseTopicId) {
        Evidence evidence = loadEvidence(planId, courseTopicId);
        if (evidence == null || evidence.totalCount() == 0) {
            return Optional.empty();
        }

        AiOperation operation = operations.require(AiOperations.WEAKNESS_EXPLAIN);

        // Correction A2: identity is what the note is about plus what the learner
        // has done since. Answering more questions on the topic changes the
        // evidence, so it must produce a new note rather than serve the old one.
        ArtifactKey key = ArtifactKey.of("WEAKNESS_EXPLANATION")
                .semantic("planId", planId.toString())
                .semantic("courseTopicId", courseTopicId.toString())
                .semantic("evidence", evidence.correctCount() + "/" + evidence.totalCount())
                .semantic("missedQuestionIds", String.join(",", evidence.missedQuestionIds()))
                .operation(operation)
                .build();

        String structuredInput = Json.write(new LinkedHashMap<>(Map.of(
                "topicTitle", evidence.topicTitle(),
                "totalCount", evidence.totalCount(),
                "correctCount", evidence.correctCount(),
                "missedQuestions", evidence.missedStems(),
                "maxWords", MAX_WORDS)));

        String prompt = prompts.render(
                prompts.load(operation.id(), operation.promptVersion()),
                Map.of(
                        "maxWords", String.valueOf(MAX_WORDS),
                        "topicTitle", evidence.topicTitle(),
                        "totalCount", String.valueOf(evidence.totalCount()),
                        "correctCount", String.valueOf(evidence.correctCount()),
                        "missedQuestions", evidence.missedStems().isEmpty()
                                ? "(không có câu sai gần đây)"
                                : "- " + String.join("\n- ", evidence.missedStems())));

        AiResult result = gateway.execute(operation.id(), planId, key, prompt, structuredInput);

        String payload;
        if (result instanceof AiResult.Ok ok) {
            payload = ok.payload();
        } else if (result instanceof AiResult.Degraded degraded) {
            payload = degraded.payload();
        } else {
            log.warn("weakness explanation unavailable for topic {}: {}", courseTopicId, result);
            return Optional.empty();
        }

        return parse(evidence, payload);
    }

    private Optional<Explanation> parse(Evidence evidence, String payload) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            List<String> reminders = new ArrayList<>();
            root.path("keyReminders").forEach(node -> reminders.add(node.asText()));
            return Optional.of(new Explanation(
                    evidence.courseTopicId(), evidence.topicTitle(),
                    root.path("summary").asText(""), reminders));
        } catch (Exception e) {
            // The gateway already validated this payload; a failure here means the
            // stored artifact is corrupt, which is worth a log and not a 500.
            log.warn("stored weakness explanation was unreadable for topic {}",
                    evidence.courseTopicId());
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- evidence

    private Evidence loadEvidence(UUID planId, UUID courseTopicId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ct.title, tm.correct_count, tm.total_count "
                        + "  FROM topic_mastery tm JOIN course_topic ct ON ct.id = tm.course_topic_id "
                        + " WHERE tm.plan_id = ? AND tm.course_topic_id = ?",
                planId, courseTopicId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);

        // Most recent first: the stems the learner just got wrong are the ones
        // worth explaining, and they also make the artifact key move.
        List<Map<String, Object>> missed = jdbc.queryForList(
                "SELECT q.id, q.stem FROM attempt_question aq "
                        + "  JOIN quiz_attempt a ON a.id = aq.attempt_id "
                        + "  JOIN question q ON q.id = aq.question_id "
                        + " WHERE a.plan_id = ? AND q.course_topic_id = ? AND aq.is_correct = false "
                        + " ORDER BY aq.answered_at DESC NULLS LAST LIMIT " + MAX_MISSED_QUESTIONS,
                planId, courseTopicId);

        List<String> ids = new ArrayList<>();
        List<String> stems = new ArrayList<>();
        for (Map<String, Object> question : missed) {
            ids.add(String.valueOf(question.get("id")));
            stems.add(String.valueOf(question.get("stem")));
        }
        // Sorted so the same set of questions in a different order is the same key.
        ids.sort(String::compareTo);

        return new Evidence(courseTopicId, String.valueOf(row.get("title")),
                ((Number) row.get("correct_count")).intValue(),
                ((Number) row.get("total_count")).intValue(),
                ids, stems);
    }

    private record Evidence(UUID courseTopicId, String topicTitle, int correctCount, int totalCount,
                            List<String> missedQuestionIds, List<String> missedStems) {
    }

    /** @param keyReminders short exam-facing reminders; may be empty */
    public record Explanation(UUID courseTopicId, String topicTitle, String summary,
                              List<String> keyReminders) {
    }
}
