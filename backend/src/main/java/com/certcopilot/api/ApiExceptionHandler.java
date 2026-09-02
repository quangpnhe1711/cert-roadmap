package com.certcopilot.api;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import com.certcopilot.domain.assessment.QuizService;
import com.certcopilot.domain.identity.AuthException;
import com.certcopilot.domain.material.MaterialService;
import com.certcopilot.domain.planning.PlanService;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * RFC 9457 problem responses with a stable machine-readable {@code code}.
 *
 * <p>Message text is never localised server side: the client owns wording, which
 * is what lets one API serve both the Vietnamese web app and a future mobile
 * client without duplicating copy.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidationError(MethodArgumentNotValidException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://certcopilot.dev/problems/validation"));
        problem.setTitle("Validation failed");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("fields", e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList());
        return problem;
    }

    @ExceptionHandler(AuthException.class)
    public ProblemDetail onAuth(AuthException e) {
        // Credential failures are 401; everything else about an account is 400.
        boolean unauthorized = e.code().startsWith("INVALID") || e.code().startsWith("REFRESH")
                || e.code().equals("UNAUTHENTICATED");
        ProblemDetail problem = ProblemDetail.forStatus(
                unauthorized ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST);
        problem.setTitle("Authentication problem");
        problem.setDetail(e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(PlanService.PlanException.class)
    public ProblemDetail onPlan(PlanService.PlanException e) {
        // A conflict is not a bad request: the client sent something valid and the
        // server's current state refuses it. The client offers a choice rather
        // than asking the learner to correct a form field that is already right.
        HttpStatus status = switch (e.code()) {
            case "PLAN_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ACTIVE_PLAN_EXISTS" -> HttpStatus.CONFLICT;
            default -> e.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        };
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle("Plan problem");
        problem.setDetail(e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(MaterialService.MaterialException.class)
    public ProblemDetail onMaterial(MaterialService.MaterialException e) {
        HttpStatus status = switch (e.code()) {
            case "MATERIAL_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "FILE_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "EXAM_DUMP_REJECTED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle("Material problem");
        problem.setDetail(e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(QuizService.QuizException.class)
    public ProblemDetail onQuiz(QuizService.QuizException e) {
        HttpStatus status = e.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle("Quiz problem");
        problem.setDetail(e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail onUploadTooLarge(MaxUploadSizeExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setTitle("File too large");
        problem.setProperty("code", "FILE_TOO_LARGE");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Bad request");
        problem.setDetail(e.getMessage());
        problem.setProperty("code", "BAD_REQUEST");
        return problem;
    }
}
