package com.certcopilot.domain.assessment.internal.grading;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic grading.
 *
 * <p>Architecture rule R2 forbids this package from depending on
 * {@code platform.ai}, and the build fails if that is ever violated. Asking a
 * model whether an answer is right would be both slower and less correct than
 * comparing two sets, and it would make a learner's score non-reproducible.
 *
 * <p>Pure: no database, no clock, no I/O. Everything it needs is in the arguments.
 */
public final class GradingService {

    private GradingService() {
    }

    /**
     * Grades one answer.
     *
     * <p>Multiple-response questions are all-or-nothing. Partial credit sounds
     * kinder but corrupts the mastery signal: a learner who picks one right and
     * one wrong answer has not understood the distinction the question tests.
     */
    public static boolean isCorrect(Set<String> selectedOptionIds, Set<String> correctOptionIds) {
        if (selectedOptionIds == null || correctOptionIds == null) {
            return false;
        }
        Set<String> expected = normalise(correctOptionIds);
        if (expected.isEmpty()) {
            // Two empty sets compare equal, which would score a question that has
            // no answer key as correct. A validator two layers away guarantees the
            // key is non-empty; depending on that from here is how scoring bugs
            // survive a refactor of the validator.
            return false;
        }
        return normalise(selectedOptionIds).equals(expected);
    }

    /** Grades a whole attempt in one pass. */
    public static Score grade(List<Answer> answers) {
        int correct = 0;
        for (Answer answer : answers) {
            if (isCorrect(answer.selectedOptionIds(), answer.correctOptionIds())) {
                correct++;
            }
        }
        return new Score(correct, answers.size());
    }

    private static Set<String> normalise(Set<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out.add(id.strip().toUpperCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }

    public record Answer(String questionId, Set<String> selectedOptionIds, Set<String> correctOptionIds) {
    }

    public record Score(int correct, int total) {
        public double accuracy() {
            return total == 0 ? 0 : (double) correct / total;
        }

        public int percent() {
            return total == 0 ? 0 : (int) Math.round(accuracy() * 100);
        }
    }
}
