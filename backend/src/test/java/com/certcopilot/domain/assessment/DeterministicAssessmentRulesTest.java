package com.certcopilot.domain.assessment;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.certcopilot.domain.assessment.internal.grading.GradingService;
import com.certcopilot.domain.assessment.internal.mastery.MasteryRules;
import com.certcopilot.shared.MasteryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two rule sets a model is forbidden to touch.
 *
 * <p>ArchUnit R2 and R3 prove no model <em>can</em> reach them. Nothing until now
 * proved they compute the right answer, which is the half that decides what a
 * learner is told about their own progress.
 *
 * <p>Both are pure functions, so this is cheap and exhaustive where it matters.
 */
class DeterministicAssessmentRulesTest {

    @Nested
    @DisplayName("grading")
    class Grading {

        @Test
        @DisplayName("an exact match is correct, in any order and any case")
        void exactMatchIsCorrect() {
            assertThat(GradingService.isCorrect(Set.of("A"), Set.of("A"))).isTrue();
            assertThat(GradingService.isCorrect(Set.of("B", "A"), Set.of("A", "B"))).isTrue();
            assertThat(GradingService.isCorrect(Set.of(" a "), Set.of("A"))).isTrue();
        }

        @Test
        @DisplayName("multiple response is all or nothing")
        void multipleResponseIsAllOrNothing() {
            // Partial credit sounds kinder and corrupts the mastery signal: a
            // learner who picks one right and one wrong has not understood the
            // distinction the question tests.
            assertThat(GradingService.isCorrect(Set.of("A"), Set.of("A", "B"))).isFalse();
            assertThat(GradingService.isCorrect(Set.of("A", "C"), Set.of("A", "B"))).isFalse();
            assertThat(GradingService.isCorrect(Set.of("A", "B", "C"), Set.of("A", "B"))).isFalse();
        }

        @Test
        @DisplayName("an unanswered question is never correct")
        void unansweredIsNeverCorrect() {
            assertThat(GradingService.isCorrect(Set.of(), Set.of("A"))).isFalse();
            assertThat(GradingService.isCorrect(null, Set.of("A"))).isFalse();
            // Two empty sets are equal, which would score a question with no answer
            // key as correct. The key is guaranteed non-empty by a validator two
            // layers away; relying on that from here is how scoring bugs happen.
            assertThat(GradingService.isCorrect(Set.of(), Set.of()))
                    .as("a question with no correct answer cannot be answered correctly")
                    .isFalse();
        }

        @Test
        @DisplayName("a whole attempt is scored in one pass, and the same answers always score the same")
        void attemptScoring() {
            List<GradingService.Answer> answers = List.of(
                    new GradingService.Answer("q1", Set.of("A"), Set.of("A")),
                    new GradingService.Answer("q2", Set.of("B"), Set.of("C")),
                    new GradingService.Answer("q3", Set.of("A", "B"), Set.of("B", "A")),
                    new GradingService.Answer("q4", Set.of(), Set.of("D")));

            GradingService.Score score = GradingService.grade(answers);

            assertThat(score.correct()).isEqualTo(2);
            assertThat(score.total()).isEqualTo(4);
            assertThat(score.percent()).isEqualTo(50);
            assertThat(GradingService.grade(answers)).isEqualTo(score);
        }

        @Test
        @DisplayName("an empty attempt scores zero rather than dividing by zero")
        void emptyAttempt() {
            GradingService.Score score = GradingService.grade(List.of());
            assertThat(score.percent()).isZero();
            assertThat(score.accuracy()).isZero();
        }
    }

    @Nested
    @DisplayName("mastery")
    class Mastery {

        @Test
        @DisplayName("five right answers in one sitting is not mastery")
        void oneSessionIsNotMastery() {
            // The locked rule, and the one that makes MASTERED mean anything: a
            // learner who happens to get five right in a row has not retained it.
            LocalDate today = LocalDate.of(2026, 3, 1);
            MasteryRules.Evidence oneSession = evidence(5, 5, 1, today, today);

            assertThat(MasteryRules.status(oneSession)).isNotEqualTo(MasteryStatus.MASTERED);
        }

        @Test
        @DisplayName("two sessions on the same day is not mastery either")
        void twoSessionsSameDayIsNotMastery() {
            LocalDate today = LocalDate.of(2026, 3, 1);
            assertThat(MasteryRules.status(evidence(6, 6, 2, today, today)))
                    .as("evidence has to be spread across time, not across one afternoon")
                    .isNotEqualTo(MasteryStatus.MASTERED);
        }

        @Test
        @DisplayName("sustained accuracy across two sessions a day apart is mastery")
        void sustainedAccuracyIsMastery() {
            MasteryRules.Evidence sustained = evidence(6, 7, 2,
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2));

            assertThat(sustained.accuracy()).isGreaterThanOrEqualTo(MasteryRules.MASTERY_ACCURACY);
            assertThat(MasteryRules.status(sustained)).isEqualTo(MasteryStatus.MASTERED);
        }

        @Test
        @DisplayName("high accuracy on too few answers is not mastery")
        void tooFewAnswers() {
            assertThat(MasteryRules.status(evidence(4, 4, 2,
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3))))
                    .isNotEqualTo(MasteryStatus.MASTERED);
        }

        @Test
        @DisplayName("low accuracy with enough evidence is REVIEW; without enough evidence it is not")
        void weaknessNeedsEvidence() {
            LocalDate day = LocalDate.of(2026, 3, 1);
            assertThat(MasteryRules.status(evidence(1, 4, 1, day, day)))
                    .isEqualTo(MasteryStatus.REVIEW);
            // Two wrong answers out of two is not yet a signal worth spending
            // schedule time on; it is a bad five minutes.
            assertThat(MasteryRules.status(evidence(0, 2, 1, day, day)))
                    .isEqualTo(MasteryStatus.LEARNING);
        }

        @Test
        @DisplayName("nothing attempted and nothing completed is NOT_STARTED")
        void notStarted() {
            assertThat(MasteryRules.status(evidence(0, 0, 0, null, null)))
                    .isEqualTo(MasteryStatus.NOT_STARTED);
        }

        @Test
        @DisplayName("a heavily weighted domain gets a lower weakness bar")
        void heavyDomainsAreJudgedHarder() {
            LocalDate day = LocalDate.of(2026, 3, 1);
            MasteryRules.Evidence adequate = evidence(3, 4, 1, day, day);   // 0.75

            assertThat(MasteryRules.isWeak(adequate, 28, 0))
                    .as("being merely adequate on 28% of the exam is a real risk")
                    .isTrue();
            assertThat(MasteryRules.isWeak(adequate, 5, 0))
                    .as("the same accuracy on 5% of the exam is not worth review time")
                    .isFalse();
        }

        @Test
        @DisplayName("repeated recent mistakes are weakness regardless of the domain")
        void recentMistakesAreWeakness() {
            LocalDate day = LocalDate.of(2026, 3, 1);
            assertThat(MasteryRules.isWeak(evidence(9, 10, 2, day, day.plusDays(2)), 5, 2)).isTrue();
        }

        @Test
        @DisplayName("a single miss on an essential concept is queued for tomorrow")
        void warmupIsTierOne() {
            // Needed because with roughly one question per topic per day the
            // three-answer threshold cannot fire during the first week.
            assertThat(MasteryRules.shouldQueueForWarmup(false, "MUST_KNOW")).isTrue();
            assertThat(MasteryRules.shouldQueueForWarmup(true, "MUST_KNOW")).isFalse();
            assertThat(MasteryRules.shouldQueueForWarmup(false, "GOOD_TO_KNOW")).isFalse();
        }

        @Test
        @DisplayName("review time is ranked by what it costs in marks")
        void rankingPrefersExpensiveGaps() {
            LocalDate day = LocalDate.of(2026, 3, 1);
            MasteryRules.Evidence weak = evidence(1, 4, 1, day, day);

            double heavyDomain = MasteryRules.weaknessScore(weak, 28, 1.0);
            double lightDomain = MasteryRules.weaknessScore(weak, 5, 1.0);
            double lessRelevant = MasteryRules.weaknessScore(weak, 28, 0.5);

            assertThat(heavyDomain).isGreaterThan(lightDomain);
            assertThat(heavyDomain).isGreaterThan(lessRelevant);
        }

        private MasteryRules.Evidence evidence(int correct, int total, int sessions,
                                               LocalDate first, LocalDate last) {
            return new MasteryRules.Evidence(correct, total, sessions, first, last, false);
        }
    }
}
