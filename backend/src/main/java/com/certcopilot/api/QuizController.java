package com.certcopilot.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.certcopilot.api.security.CurrentUser;
import com.certcopilot.domain.assessment.FinalReviewExamService;
import com.certcopilot.domain.assessment.QuizService;
import com.certcopilot.domain.assessment.WeaknessExplanationService;
import com.certcopilot.domain.planning.PlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quiz attempts and the final review exam.
 *
 * <p>Every response carries the "AI-generated practice question" marker, and the
 * client shows it permanently. These are not real exam questions and the product
 * never implies otherwise.
 */
@RestController
@RequestMapping("/api/v1")
public class QuizController {

    private static final String AI_NOTICE =
            "Câu hỏi luyện tập do AI tạo — không phải đề thi chính thức.";

    private final QuizService quizzes;
    private final FinalReviewExamService finalExam;
    private final PlanService plans;
    private final WeaknessExplanationService weakness;

    public QuizController(QuizService quizzes, FinalReviewExamService finalExam, PlanService plans,
                          WeaknessExplanationService weakness) {
        this.quizzes = quizzes;
        this.finalExam = finalExam;
        this.plans = plans;
        this.weakness = weakness;
    }

    @PostMapping("/plans/{planId}/quizzes")
    public Map<String, Object> start(@PathVariable UUID planId,
                                     @RequestBody @Valid StartQuizRequest request) {
        plans.requireOwned(CurrentUser.id(), planId);

        QuizService.AttemptView attempt = switch (request.kind()) {
            case "WARMUP" -> quizzes.startWarmup(planId);
            case "FINAL_REVIEW" -> finalExam.start(planId, request.size());
            default -> quizzes.startDailyQuiz(planId, request.studyDayId());
        };
        return Map.of("attempt", attempt, "notice", AI_NOTICE);
    }

    @GetMapping("/plans/{planId}/quizzes/{attemptId}")
    public Map<String, Object> attempt(@PathVariable UUID planId, @PathVariable UUID attemptId,
                                       @RequestParam(defaultValue = "false") boolean withAnswers) {
        plans.requireOwned(CurrentUser.id(), planId);
        return Map.of("attempt", quizzes.loadAttempt(planId, attemptId, withAnswers),
                "notice", AI_NOTICE);
    }

    @PostMapping("/plans/{planId}/quizzes/{attemptId}/answers")
    public ResponseEntity<Void> answer(@PathVariable UUID planId, @PathVariable UUID attemptId,
                                       @RequestBody @Valid AnswerRequest request) {
        plans.requireOwned(CurrentUser.id(), planId);
        // Saved per question so a dropped connection does not lose the attempt.
        quizzes.answer(planId, attemptId, request.questionId(), request.selectedOptionIds());
        return ResponseEntity.noContent().build();
    }

    /** Deterministic grading. No model is consulted. */
    @PostMapping("/plans/{planId}/quizzes/{attemptId}/submit")
    public Map<String, Object> submit(@PathVariable UUID planId, @PathVariable UUID attemptId) {
        plans.requireOwned(CurrentUser.id(), planId);
        return Map.of("attempt", quizzes.submit(planId, attemptId), "notice", AI_NOTICE);
    }

    // -------------------------------------------------------- weak topics

    /**
     * The short review note for one weak topic, generated on first request.
     *
     * <p>204 rather than an error when there is nothing to say: the weak-topic row
     * itself is deterministic and already on screen, and a red banner because an
     * optional explanation was refused at the budget cap would be worse than the
     * missing paragraph.
     */
    @GetMapping("/plans/{planId}/weak-topics/{courseTopicId}/explanation")
    public ResponseEntity<WeaknessExplanationService.Explanation> weaknessExplanation(
            @PathVariable UUID planId, @PathVariable UUID courseTopicId) {
        plans.requireOwned(CurrentUser.id(), planId);
        return weakness.explain(planId, courseTopicId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // ----------------------------------------------------------- final review

    @GetMapping("/plans/{planId}/final-exam/readiness")
    public FinalReviewExamService.Readiness readiness(@PathVariable UUID planId) {
        plans.requireOwned(CurrentUser.id(), planId);
        return finalExam.readiness(planId);
    }

    @GetMapping("/plans/{planId}/final-exam/{attemptId}/result")
    public FinalReviewExamService.ExamResult result(@PathVariable UUID planId,
                                                    @PathVariable UUID attemptId) {
        plans.requireOwned(CurrentUser.id(), planId);
        return finalExam.result(planId, attemptId);
    }

    public record StartQuizRequest(@NotNull String kind, UUID studyDayId, Integer size) {
    }

    public record AnswerRequest(@NotNull UUID questionId, List<String> selectedOptionIds) {
    }
}
