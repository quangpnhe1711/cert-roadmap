package com.certcopilot.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correction P3, second half: prove each rule detects what it claims to detect.
 *
 * <p>The coverage test proves a rule is not vacuous. This test proves it is not
 * merely <em>toothless</em>: the same rule object used against production code is
 * run against two fixtures - one compliant, one deliberately violating - and must
 * accept the first and reject the second.
 *
 * <p>Together the two tests give the guarantee we actually want:
 * <em>green ArchUnit means the boundary is enforced.</em>
 */
class ArchitectureRuleVerificationTest {

    private static final String FIXTURE_ROOT = "com.certcopilot.archfixture";

    @Test
    @DisplayName("R1 accepts a scheduler with no AI dependency")
    void r1PassesOnCompliantFixture() {
        EvaluationResult result = evaluate(rule("R1"), FIXTURE_ROOT + ".allowed");
        assertThat(result.hasViolation())
                .as("a scheduler that does not touch platform.ai must satisfy R1")
                .isFalse();
    }

    @Test
    @DisplayName("R1 detects a scheduler that reaches into the AI platform")
    void r1FailsOnViolatingFixture() {
        EvaluationResult result = evaluate(rule("R1"), FIXTURE_ROOT + ".forbidden");
        assertThat(result.hasViolation())
                .as("R1 must reject a scheduler that depends on platform.ai, "
                        + "otherwise the rule is decorative")
                .isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails()))
                .contains("ViolatingScheduler");
    }

    @Test
    @DisplayName("R2 accepts deterministic grading")
    void r2PassesOnCompliantFixture() {
        EvaluationResult result = evaluate(rule("R2"), FIXTURE_ROOT + ".allowed");
        assertThat(result.hasViolation()).isFalse();
    }

    @Test
    @DisplayName("R2 detects grading that asks a model to judge an answer")
    void r2FailsOnViolatingFixture() {
        EvaluationResult result = evaluate(rule("R2"), FIXTURE_ROOT + ".forbidden");
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails()))
                .contains("ViolatingGrader");
    }

    @Test
    @DisplayName("R5 accepts a feature that goes through the AI gateway")
    void r5PassesOnCompliantFixture() {
        EvaluationResult result = evaluate(rule("R5"), FIXTURE_ROOT + ".allowed");
        assertThat(result.hasViolation())
                .as("reaching a model through AiGateway is the sanctioned path")
                .isFalse();
    }

    @Test
    @DisplayName("R5 detects a feature that calls the model port directly")
    void r5FailsOnViolatingFixture() {
        EvaluationResult result = evaluate(rule("R5"), FIXTURE_ROOT + ".forbidden");
        assertThat(result.hasViolation())
                .as("calling LlmPort directly skips cache, budget, ledger and every "
                        + "validator in one line; R5 must reject it")
                .isTrue();
        assertThat(String.join(System.lineSeparator(), result.getFailureReport().getDetails()))
                .contains("ViolatingAiCaller");
    }

    @Test
    @DisplayName("R8 accepts a plan route that checks ownership")
    void r8PassesOnCompliantFixture() {
        EvaluationResult result = evaluate(rule("R8"), FIXTURE_ROOT + ".allowed");
        assertThat(result.hasViolation()).isFalse();
    }

    @Test
    @DisplayName("R8 detects a plan route that trusts the id in the URL")
    void r8FailsOnViolatingFixture() {
        EvaluationResult result = evaluate(rule("R8"), FIXTURE_ROOT + ".forbidden");
        assertThat(result.hasViolation())
                .as("an unchecked planId route serves one learner's material to another; "
                        + "R8 must reject it")
                .isTrue();
        assertThat(String.join(System.lineSeparator(), result.getFailureReport().getDetails()))
                .contains("LeakyPlanRouteController");
    }

    @Test
    @DisplayName("the verification fixtures themselves exist and are compiled")
    void fixturesArePresent() {
        JavaClasses allowed = importFixture(FIXTURE_ROOT + ".allowed");
        JavaClasses forbidden = importFixture(FIXTURE_ROOT + ".forbidden");

        // If the fixtures ever disappear, the two tests above would pass trivially
        // and P3 would be silently undone.
        assertThat(allowed).as("compliant fixtures must exist").isNotEmpty();
        assertThat(forbidden).as("violating fixtures must exist").isNotEmpty();
    }

    private static ArchRule rule(String id) {
        return ArchitectureRules.all().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no architecture rule with id " + id))
                .rule();
    }

    private static EvaluationResult evaluate(ArchRule rule, String fixturePackage) {
        return rule.evaluate(importFixture(fixturePackage));
    }

    /** Imports fixture classes only. Test classes are included here on purpose. */
    private static JavaClasses importFixture(String fixturePackage) {
        return new ClassFileImporter().importPackages(fixturePackage);
    }
}
