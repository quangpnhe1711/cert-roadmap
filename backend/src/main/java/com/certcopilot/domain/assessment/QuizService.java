package com.certcopilot.domain.assessment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.certcopilot.domain.assessment.internal.grading.GradingService;
import com.certcopilot.domain.assessment.internal.mastery.MasteryRules;
import com.certcopilot.platform.ai.AiGateway;
import com.certcopilot.platform.ai.AiOperationRegistry;
import com.certcopilot.platform.ai.AiOperations;
import com.certcopilot.platform.ai.AiResult;
import com.certcopilot.platform.ai.ArtifactKey;
import com.certcopilot.platform.ai.PromptRegistry;
import com.certcopilot.platform.ai.validators.QuizValidator;
import com.certcopilot.shared.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Question generation, attempts and grading.
 *
 * <p>Generation is the model's job; grading is not. A question that cannot prove
 * where its answer comes from is discarded rather than repaired, because a wrong
 * answer key is the single fastest way to lose a learner's trust and they have no
 * way to detect it themselves.
 */
@Service
public class QuizService {

    private static final Logger log = LoggerFactory.getLogger(QuizService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int DEFAULT_QUESTIONS_PER_UNIT = 4;
    private static final int MAX_QUESTIONS_PER_DAY = 10;
    private static final int WARMUP_QUESTIONS = 3;

    private final JdbcTemplate jdbc;
    private final AiGateway gateway;
    private final AiOperationRegistry operations;
    private final PromptRegistry prompts;
    private final QuizValidator quizValidator;

    public QuizService(JdbcTemplate jdbc, AiGateway gateway, AiOperationRegistry operations,
                       PromptRegistry prompts, QuizValidator quizValidator) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.operations = operations;
        this.prompts = prompts;
        this.quizValidator = quizValidator;
    }

    // ------------------------------------------------------------ generation

    /**
     * Fills the bank for one unit. Safe to call repeatedly: the artifact key means
     * an unchanged unit reuses what exists.
     *
     * @return number of questions that survived validation
     */
    @Transactional
    public int generateForUnit(UUID planId, UUID learningUnitId) {
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM question WHERE plan_id = ? AND learning_unit_id = ? "
                        + "   AND validation_status = 'VALID'",
                Integer.class, planId, learningUnitId);
        if (existing != null && existing >= DEFAULT_QUESTIONS_PER_UNIT) {
            return existing;
        }

        UnitQuizContext ctx = loadContext(planId, learningUnitId);
        if (ctx == null || ctx.taskStatements().isEmpty() || ctx.sourceText().isBlank()) {
            // No exam anchor or no source means no groundable question. Producing
            // one anyway is exactly what the validator exists to prevent.
            log.debug("skipping quiz generation for unit {}: nothing to ground against", learningUnitId);
            return 0;
        }

        var operation = operations.require(AiOperations.QUIZ_GENERATE);
        String structuredInput = Json.write(new LinkedHashMap<>(Map.of(
                "questionCount", DEFAULT_QUESTIONS_PER_UNIT,
                "pageStart", ctx.pageStart(),
                "pageEnd", ctx.pageEnd(),
                "sourceText", ctx.sourceText(),
                "taskStatements", ctx.taskStatements(),
                "testMarker", ctx.title().toLowerCase())));

        String prompt = prompts.render(
                prompts.load(operation.id(), operation.promptVersion()),
                Map.of("examCode", ctx.examCode(),
                        "questionCount", String.valueOf(DEFAULT_QUESTIONS_PER_UNIT),
                        "pageStart", String.valueOf(ctx.pageStart()),
                        "pageEnd", String.valueOf(ctx.pageEnd()),
                        "taskStatements", Json.write(ctx.taskStatements()),
                        "sourceText", ctx.sourceText()));

        ArtifactKey key = ArtifactKey.of("QUIZ_BATCH")
                .semantic("learningUnitContentHash", ctx.contentHash())
                .semantic("certificationVersionId", ctx.certificationVersionId().toString())
                .semantic("questionCount", String.valueOf(DEFAULT_QUESTIONS_PER_UNIT))
                .operation(operation)
                .build();

        AiResult result = gateway.execute(operation.id(), planId, key, prompt, structuredInput);
        String payload = switch (result) {
            case AiResult.Ok ok -> ok.payload();
            case AiResult.Degraded degraded -> degraded.payload();
            case AiResult.Failed failed -> null;
        };
        if (payload == null) {
            log.warn("quiz generation failed for unit {}", learningUnitId);
            return 0;
        }

        // Second pass: the batch passed, but individual questions may not have.
        // Only clean ones are persisted.
        List<JsonNode> valid = quizValidator.keepValid(payload, structuredInput);
        int stored = 0;
        for (JsonNode question : valid) {
            if (persistQuestion(planId, learningUnitId, ctx, question)) {
                stored++;
            }
        }
        log.info("unit {}: {} of {} generated questions passed validation",
                learningUnitId, stored, valid.size());
        return stored;
    }

    private boolean persistQuestion(UUID planId, UUID learningUnitId,
                                    UnitQuizContext ctx, JsonNode q) {
        try {
            List<String> correct = new ArrayList<>();
            q.path("correctOptionIds").forEach(n -> correct.add(n.asText()));

            jdbc.update(
                    "INSERT INTO question (id, plan_id, learning_unit_id, task_statement_id, "
                            + " course_topic_id, question_type, stem, options, correct_option_ids, "
                            + " explanation, distractor_rationales, source_span, source_page_start, "
                            + " source_page_end, difficulty, prompt_version) "
                            + "VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb,?,?,?,?,?)",
                    UUID.randomUUID(), planId, learningUnitId,
                    UUID.fromString(q.path("taskStatementId").asText()),
                    ctx.primaryTopicId(),
                    q.path("type").asText("SINGLE_CHOICE"),
                    q.path("stem").asText(),
                    q.path("options").toString(),
                    Json.write(correct),
                    q.path("explanation").asText(),
                    q.path("distractorRationales").toString(),
                    q.path("sourceSpan").asText(),
                    q.path("sourcePageStart").asInt(ctx.pageStart()),
                    q.path("sourcePageEnd").asInt(ctx.pageEnd()),
                    Math.max(1, Math.min(5, q.path("difficulty").asInt(3))),
                    operations.require(AiOperations.QUIZ_GENERATE).promptVersion());
            return true;
        } catch (Exception e) {
            log.warn("cannot persist a generated question: {}", e.toString());
            return false;
        }
    }

    // -------------------------------------------------------------- attempts

    /** Builds the daily quiz from the bank for the units studied that day. */
    @Transactional
    public AttemptView startDailyQuiz(UUID planId, UUID studyDayId) {
        List<UUID> unitIds = jdbc.queryForList(
                "SELECT learning_unit_id FROM study_day_item "
                        + " WHERE study_day_id = ? AND item_type = 'LEARNING_UNIT' "
                        + "   AND learning_unit_id IS NOT NULL",
                UUID.class, studyDayId);

        List<Map<String, Object>> pool = unitIds.isEmpty() ? List.of() : jdbc.queryForList(
                "SELECT id FROM question WHERE plan_id = ? AND validation_status = 'VALID' "
                        + "   AND learning_unit_id = ANY (?) ORDER BY random() LIMIT ?",
                planId, unitIds.toArray(new UUID[0]), MAX_QUESTIONS_PER_DAY);

        List<UUID> questionIds = pool.stream().map(r -> (UUID) r.get("id")).toList();
        if (questionIds.isEmpty()) {
            throw new QuizException("QUIZ_UNAVAILABLE",
                    "Chưa tạo được câu hỏi cho hôm nay. Bạn vẫn có thể hoàn thành ngày học.");
        }
        return createAttempt(planId, studyDayId, "DAILY", questionIds);
    }

    /**
     * Warm-up: questions the learner previously missed on essential concepts.
     * Deterministic selection from the existing bank, so it costs nothing.
     */
    @Transactional
    public AttemptView startWarmup(UUID planId) {
        List<UUID> queued = jdbc.queryForList(
                "SELECT question_id FROM warmup_queue WHERE plan_id = ? AND consumed_at IS NULL "
                        + " ORDER BY queued_at LIMIT ?",
                UUID.class, planId, WARMUP_QUESTIONS);
        if (queued.isEmpty()) {
            throw new QuizException("NO_WARMUP", "Không có câu ôn lại nào cho hôm nay.");
        }
        jdbc.update("UPDATE warmup_queue SET consumed_at = now() "
                        + " WHERE plan_id = ? AND question_id = ANY (?)",
                planId, queued.toArray(new UUID[0]));
        return createAttempt(planId, null, "WARMUP", queued);
    }

    private AttemptView createAttempt(UUID planId, UUID studyDayId, String kind, List<UUID> questionIds) {
        UUID attemptId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO quiz_attempt (id, plan_id, study_day_id, kind, score_total) VALUES (?,?,?,?,?)",
                attemptId, planId, studyDayId, kind, questionIds.size());

        int index = 0;
        for (UUID questionId : questionIds) {
            jdbc.update(
                    "INSERT INTO attempt_question (attempt_id, question_id, order_index) VALUES (?,?,?)",
                    attemptId, questionId, index++);
        }
        return loadAttempt(planId, attemptId, false);
    }

    @Transactional
    public void answer(UUID planId, UUID attemptId, UUID questionId, List<String> selectedOptionIds) {
        requireAttempt(planId, attemptId);
        jdbc.update(
                "UPDATE attempt_question SET selected_option_ids = ?::jsonb, answered_at = now() "
                        + " WHERE attempt_id = ? AND question_id = ?",
                Json.write(selectedOptionIds == null ? List.of() : selectedOptionIds),
                attemptId, questionId);
    }

    /**
     * Grades the attempt. Deterministic set comparison; no model involved, and
     * the same answers always produce the same score.
     */
    @Transactional
    public AttemptView submit(UUID planId, UUID attemptId) {
        requireAttempt(planId, attemptId);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT aq.question_id, aq.selected_option_ids, q.correct_option_ids, "
                        + "       q.course_topic_id, q.task_statement_id "
                        + "  FROM attempt_question aq JOIN question q ON q.id = aq.question_id "
                        + " WHERE aq.attempt_id = ?", attemptId);

        int correct = 0;
        for (Map<String, Object> row : rows) {
            Set<String> selected = new LinkedHashSet<>(Json.read(
                    String.valueOf(row.get("selected_option_ids")), new TypeReference<List<String>>() { }));
            Set<String> expected = new LinkedHashSet<>(Json.read(
                    String.valueOf(row.get("correct_option_ids")), new TypeReference<List<String>>() { }));

            boolean isCorrect = GradingService.isCorrect(selected, expected);
            if (isCorrect) {
                correct++;
            }
            jdbc.update("UPDATE attempt_question SET is_correct = ? WHERE attempt_id = ? AND question_id = ?",
                    isCorrect, attemptId, row.get("question_id"));

            queueWarmupIfEssential(planId, (UUID) row.get("question_id"), isCorrect);
        }

        jdbc.update("UPDATE quiz_attempt SET status = 'GRADED', score_raw = ?, score_total = ?, "
                        + " submitted_at = now() WHERE id = ?",
                correct, rows.size(), attemptId);

        recomputeMastery(planId);
        return loadAttempt(planId, attemptId, true);
    }

    /**
     * Tier-one weakness signal: one miss on a MUST_KNOW concept is enough to
     * re-ask tomorrow. Waiting for three answers would never fire in week one.
     */
    private void queueWarmupIfEssential(UUID planId, UUID questionId, boolean wasCorrect) {
        if (wasCorrect) {
            return;
        }
        Boolean essential = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM knowledge_item ki JOIN question q "
                        + "        ON q.task_statement_id = ki.task_statement_id "
                        + "     WHERE q.id = ? AND ki.is_must_know)",
                Boolean.class, questionId);
        if (Boolean.TRUE.equals(essential)) {
            jdbc.update("INSERT INTO warmup_queue (id, plan_id, question_id) VALUES (?,?,?) "
                            + "ON CONFLICT (plan_id, question_id) DO NOTHING",
                    UUID.randomUUID(), planId, questionId);
        }
    }

    // --------------------------------------------------------------- mastery

    /**
     * Recomputes mastery from answer history. The table is a cache: deleting it
     * and running this again must produce the same result.
     */
    @Transactional
    public void recomputeMastery(UUID planId) {
        List<Map<String, Object>> stats = jdbc.queryForList(
                "SELECT q.course_topic_id, "
                        + "       count(*) FILTER (WHERE aq.is_correct IS NOT NULL) AS total, "
                        + "       count(*) FILTER (WHERE aq.is_correct) AS correct, "
                        + "       count(DISTINCT date(aq.answered_at)) AS sessions, "
                        + "       min(date(aq.answered_at)) AS first_day, "
                        + "       max(date(aq.answered_at)) AS last_day, "
                        + "       count(*) FILTER (WHERE aq.is_correct = false "
                        + "              AND aq.answered_at > now() - interval '7 days') AS recent_mistakes "
                        + "  FROM attempt_question aq "
                        + "  JOIN question q ON q.id = aq.question_id "
                        + "  JOIN quiz_attempt a ON a.id = aq.attempt_id "
                        + " WHERE a.plan_id = ? AND q.course_topic_id IS NOT NULL "
                        + "   AND aq.is_correct IS NOT NULL "
                        + " GROUP BY q.course_topic_id", planId);

        for (Map<String, Object> row : stats) {
            UUID topicId = (UUID) row.get("course_topic_id");
            int total = ((Number) row.get("total")).intValue();
            int correct = ((Number) row.get("correct")).intValue();
            int sessions = ((Number) row.get("sessions")).intValue();
            int recentMistakes = ((Number) row.get("recent_mistakes")).intValue();
            LocalDate firstDay = row.get("first_day") == null ? null
                    : ((java.sql.Date) row.get("first_day")).toLocalDate();
            LocalDate lastDay = row.get("last_day") == null ? null
                    : ((java.sql.Date) row.get("last_day")).toLocalDate();

            var evidence = new MasteryRules.Evidence(
                    correct, total, sessions, firstDay, lastDay, true);

            jdbc.update(
                    "INSERT INTO topic_mastery (plan_id, course_topic_id, correct_count, total_count, "
                            + " mistake_count, sessions_count, first_session_on, last_session_on, "
                            + " last_assessed_at, status) VALUES (?,?,?,?,?,?,?,?, now(), ?) "
                            + "ON CONFLICT (plan_id, course_topic_id) DO UPDATE SET "
                            + " correct_count = EXCLUDED.correct_count, total_count = EXCLUDED.total_count, "
                            + " mistake_count = EXCLUDED.mistake_count, sessions_count = EXCLUDED.sessions_count, "
                            + " first_session_on = EXCLUDED.first_session_on, "
                            + " last_session_on = EXCLUDED.last_session_on, "
                            + " last_assessed_at = now(), status = EXCLUDED.status",
                    planId, topicId, correct, total, total - correct, sessions,
                    firstDay, lastDay, MasteryRules.status(evidence).name());
        }
    }

    // ---------------------------------------------------------------- views

    @Transactional(readOnly = true)
    public AttemptView loadAttempt(UUID planId, UUID attemptId, boolean withAnswers) {
        Map<String, Object> attempt = jdbc.queryForMap(
                "SELECT id, kind, status, score_raw, score_total, study_day_id "
                        + "  FROM quiz_attempt WHERE id = ? AND plan_id = ?", attemptId, planId);

        List<QuestionView> questions = jdbc.query(
                "SELECT q.id, q.question_type, q.stem, q.options, q.correct_option_ids, q.explanation, "
                        + "       q.distractor_rationales, q.source_span, q.source_page_start, "
                        + "       q.source_page_end, q.difficulty, aq.selected_option_ids, aq.is_correct, "
                        + "       t.code AS task_code, t.title AS task_title, d.title AS domain_title "
                        + "  FROM attempt_question aq JOIN question q ON q.id = aq.question_id "
                        + "  JOIN task_statement t ON t.id = q.task_statement_id "
                        + "  JOIN exam_domain d ON d.id = t.exam_domain_id "
                        + " WHERE aq.attempt_id = ? ORDER BY aq.order_index",
                (rs, n) -> new QuestionView(
                        rs.getObject("id", UUID.class),
                        rs.getString("question_type"),
                        rs.getString("stem"),
                        Json.read(rs.getString("options"), new TypeReference<List<Map<String, Object>>>() { }),
                        withAnswers ? Json.read(rs.getString("correct_option_ids"),
                                new TypeReference<List<String>>() { }) : null,
                        withAnswers ? rs.getString("explanation") : null,
                        withAnswers ? Json.read(rs.getString("distractor_rationales"),
                                new TypeReference<Map<String, String>>() { }) : null,
                        withAnswers ? rs.getString("source_span") : null,
                        (Integer) rs.getObject("source_page_start"),
                        (Integer) rs.getObject("source_page_end"),
                        rs.getInt("difficulty"),
                        Json.read(rs.getString("selected_option_ids"), new TypeReference<List<String>>() { }),
                        (Boolean) rs.getObject("is_correct"),
                        rs.getString("task_code"),
                        rs.getString("task_title"),
                        rs.getString("domain_title")),
                attemptId);

        return new AttemptView(
                (UUID) attempt.get("id"),
                (String) attempt.get("kind"),
                (String) attempt.get("status"),
                (Integer) attempt.get("score_raw"),
                (Integer) attempt.get("score_total"),
                (UUID) attempt.get("study_day_id"),
                questions);
    }

    private void requireAttempt(UUID planId, UUID attemptId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM quiz_attempt WHERE id = ? AND plan_id = ?",
                Integer.class, attemptId, planId);
        if (n == null || n == 0) {
            throw new QuizException("ATTEMPT_NOT_FOUND", "quiz attempt not found");
        }
    }

    private UnitQuizContext loadContext(UUID planId, UUID learningUnitId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id, u.title, u.page_start, u.page_end, u.content_hash, "
                        + "       u.material_revision_id, u.course_topic_ids, "
                        + "       p.certification_version_id, cv.exam_code "
                        + "  FROM learning_unit u "
                        + "  JOIN plan_unit pu ON pu.learning_unit_id = u.id AND pu.plan_id = ? "
                        + "  JOIN study_plan p ON p.id = pu.plan_id "
                        + "  JOIN certification_version cv ON cv.id = p.certification_version_id "
                        + " WHERE u.id = ?", planId, learningUnitId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        int pageStart = row.get("page_start") == null ? 1 : ((Number) row.get("page_start")).intValue();
        int pageEnd = row.get("page_end") == null ? pageStart : ((Number) row.get("page_end")).intValue();
        UUID revisionId = (UUID) row.get("material_revision_id");

        List<Map<String, Object>> tasks = jdbc.queryForList(
                "SELECT DISTINCT t.id, t.code, t.title FROM unit_exam_mapping m "
                        + "  JOIN task_statement t ON t.id = m.task_statement_id "
                        + " WHERE m.learning_unit_id = ? AND m.certification_version_id = ?",
                learningUnitId, row.get("certification_version_id"));

        List<Map<String, Object>> taskInput = tasks.stream()
                .map(t -> (Map<String, Object>) new LinkedHashMap<String, Object>(Map.of(
                        "id", String.valueOf(t.get("id")),
                        "code", String.valueOf(t.get("code")),
                        "title", String.valueOf(t.get("title")))))
                .toList();

        String sourceText = revisionId == null ? "" : String.join("\n",
                jdbc.queryForList(
                        "SELECT text FROM material_page WHERE material_revision_id = ? "
                                + "   AND page_no BETWEEN ? AND ? ORDER BY page_no",
                        String.class, revisionId, pageStart, pageEnd));

        List<String> topicIds = Json.read(String.valueOf(row.get("course_topic_ids")),
                new TypeReference<List<String>>() { });
        UUID primaryTopic = topicIds.isEmpty() ? null : UUID.fromString(topicIds.get(0));

        return new UnitQuizContext(
                learningUnitId, (String) row.get("title"), pageStart, pageEnd,
                (String) row.get("content_hash"), (UUID) row.get("certification_version_id"),
                (String) row.get("exam_code"), taskInput,
                sourceText.length() > 20_000 ? sourceText.substring(0, 20_000) : sourceText,
                primaryTopic);
    }

    // --------------------------------------------------------------- records

    public record UnitQuizContext(UUID learningUnitId, String title, int pageStart, int pageEnd,
                                  String contentHash, UUID certificationVersionId, String examCode,
                                  List<Map<String, Object>> taskStatements, String sourceText,
                                  UUID primaryTopicId) {
    }

    public record QuestionView(UUID id, String type, String stem, List<Map<String, Object>> options,
                               List<String> correctOptionIds, String explanation,
                               Map<String, String> distractorRationales, String sourceSpan,
                               Integer sourcePageStart, Integer sourcePageEnd, int difficulty,
                               List<String> selectedOptionIds, Boolean isCorrect,
                               String taskCode, String taskTitle, String domainTitle) {
    }

    public record AttemptView(UUID id, String kind, String status, Integer scoreRaw, Integer scoreTotal,
                              UUID studyDayId, List<QuestionView> questions) {
    }

    public static class QuizException extends RuntimeException {
        private final String code;

        public QuizException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
