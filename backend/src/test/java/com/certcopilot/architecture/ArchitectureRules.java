package com.certcopilot.architecture;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The production architecture rules, defined once and reused by three tests:
 *
 * <ul>
 *   <li>{@link ArchitectureRulesTest} runs them against production classes;
 *   <li>{@link ArchitectureRuleCoverageTest} proves none of them passes vacuously;
 *   <li>{@link ArchitectureRuleVerificationTest} proves each rule actually detects
 *       the violation it claims to detect.
 * </ul>
 *
 * <p>R1-R3 translate the product principle "the model never owns scheduling or
 * scoring" into a build-time constraint. Without them the principle survives
 * until the first week somebody finds it convenient to break.
 */
public final class ArchitectureRules {

    public static final String ROOT = "com.certcopilot";

    private ArchitectureRules() {
    }

    /** Production classes only: no test classes, and therefore no fixtures. */
    public static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ROOT);
    }

    public static List<NamedRule> all() {
        return List.of(
                new NamedRule("R1",
                        "the scheduler must not depend on the AI platform",
                        noClasses().that().resideInAPackage("..planning.internal.scheduler..")
                                .should().dependOnClassesThat().resideInAPackage("..platform.ai..")
                                .allowEmptyShould(false),
                        "..planning.internal.scheduler..",
                        null),

                new NamedRule("R2",
                        "quiz grading must not depend on the AI platform",
                        noClasses().that().resideInAPackage("..assessment.internal.grading..")
                                .should().dependOnClassesThat().resideInAPackage("..platform.ai..")
                                .allowEmptyShould(false),
                        "..assessment.internal.grading..",
                        null),

                new NamedRule("R3",
                        "mastery calculation must not depend on the AI platform",
                        noClasses().that().resideInAPackage("..assessment.internal.mastery..")
                                .should().dependOnClassesThat().resideInAPackage("..platform.ai..")
                                .allowEmptyShould(false),
                        "..assessment.internal.mastery..",
                        null),

                new NamedRule("R4",
                        "the platform layer must not depend on the domain layer",
                        noClasses().that().resideInAPackage("..platform..")
                                .should().dependOnClassesThat().resideInAPackage("..domain..")
                                .allowEmptyShould(false),
                        "..platform..",
                        null),

                new NamedRule("R5",
                        "only the AI gateway may reach the model port",
                        noClasses().that()
                                .doNotHaveFullyQualifiedName("com.certcopilot.platform.ai.AiGateway")
                                .and().areNotAssignableTo(com.certcopilot.platform.ai.LlmPort.class)
                                .should().dependOnClassesThat()
                                .areAssignableTo(com.certcopilot.platform.ai.LlmPort.class)
                                .allowEmptyShould(false),
                        "..",
                        null),

                new NamedRule("R6",
                        "only the AI platform may reference a model provider SDK",
                        noClasses().that().resideOutsideOfPackage("..platform.ai..")
                                .should().dependOnClassesThat().resideInAnyPackage(
                                        "com.anthropic..", "com.openai..", "dev.langchain4j..",
                                        "com.azure.ai..", "com.google.cloud.vertexai..")
                                .allowEmptyShould(false),
                        "..",
                        null),

                new NamedRule("R8",
                        "every HTTP handler for a plan route must check plan ownership",
                        methods().that(handlePlanRoutes())
                                .should(checkPlanOwnership())
                                .allowEmptyShould(false),
                        "..api..",
                        null),

                new NamedRule("R7",
                        "there must be no cyclic dependencies between top-level modules",
                        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles(),
                        "..",
                        null));
    }

    /**
     * Selects HTTP handlers whose route carries a {@code planId}.
     *
     * <p>Chosen from the mapping annotation rather than the parameter name,
     * because parameter names are a compiler flag away from disappearing.
     */
    private static DescribedPredicate<JavaMethod> handlePlanRoutes() {
        return new DescribedPredicate<>("handle a {planId} route") {
            @Override
            public boolean test(JavaMethod method) {
                return mappedPaths(method).stream().anyMatch(path -> path.contains("{planId}"));
            }
        };
    }

    /**
     * Requires the handler to reach {@code requireOwned}, directly or through the
     * service it delegates to.
     *
     * <p>Two levels, because both styles are used and both are correct: some
     * handlers check and then call, others pass the caller's identity down and
     * let the service check. What is not correct is neither, and that is exactly
     * how one endpoint ended up serving other people's lessons.
     */
    private static ArchCondition<JavaMethod> checkPlanOwnership() {
        return new ArchCondition<>("check plan ownership") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (!reachesOwnershipCheck(method, 2, new HashSet<>())) {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " serves a {planId} route without ever "
                                    + "checking that the caller owns the plan"));
                }
            }
        };
    }

    private static boolean reachesOwnershipCheck(JavaMethod method, int depth, Set<String> seen) {
        if (!seen.add(method.getFullName())) {
            return false;
        }
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
            if (OWNERSHIP_CHECKS.contains(call.getTarget().getName())) {
                return true;
            }
            if (depth > 0) {
                for (JavaMethod target : call.getTarget().resolveMember().stream().toList()) {
                    if (reachesOwnershipCheck(target, depth - 1, seen)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Method names that constitute an ownership check. */
    private static final Set<String> OWNERSHIP_CHECKS = Set.of("requireOwned");

    private static List<String> mappedPaths(JavaMethod method) {
        List<String> paths = new ArrayList<>();
        method.getAnnotations().stream()
                .filter(annotation -> annotation.getRawType().getSimpleName().endsWith("Mapping"))
                .forEach(annotation -> {
                    addPaths(paths, annotation.get("value").orElse(null));
                    addPaths(paths, annotation.get("path").orElse(null));
                });
        return paths;
    }

    private static void addPaths(List<String> into, Object value) {
        if (value instanceof Object[] array) {
            for (Object entry : array) {
                into.add(String.valueOf(entry));
            }
        } else if (value != null) {
            into.add(String.valueOf(value));
        }
    }

    /**
     * A rule plus the metadata the coverage test needs.
     *
     * @param targetPackage  package pattern the rule constrains
     * @param pendingUntilSlice slice that introduces the target package, or
     *                          {@code null} when the rule must already cover code.
     *                          A non-null value means the rule is knowingly vacuous
     *                          today - it is tracked, not silently green.
     */
    public record NamedRule(
            String id,
            String description,
            ArchRule rule,
            String targetPackage,
            String pendingUntilSlice) {

        public boolean isPending() {
            return pendingUntilSlice != null;
        }
    }
}
