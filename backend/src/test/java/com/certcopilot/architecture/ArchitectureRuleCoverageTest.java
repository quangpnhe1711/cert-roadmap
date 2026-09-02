package com.certcopilot.architecture;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.PackageMatchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correction P3, first half: a green architecture suite must mean "the boundary
 * is enforced", never "no class happened to match".
 *
 * <p>Every rule declares the package it constrains. A rule whose package contains
 * no production classes is only tolerated when it is explicitly marked pending
 * against the slice that will introduce that package. Two failures are possible:
 *
 * <ul>
 *   <li>a rule that should be covering code covers nothing - the rule is broken
 *       or the package was renamed;
 *   <li>a rule still marked pending whose package now exists - the marker is
 *       stale and must be removed, so the rule starts being enforced for real.
 * </ul>
 */
class ArchitectureRuleCoverageTest {

    @Test
    @DisplayName("no architecture rule passes vacuously without being declared pending")
    void everyRuleEitherCoversClassesOrIsDeclaredPending() {
        JavaClasses production = ArchitectureRules.productionClasses();
        assertThat(production)
                .as("production classes must be importable, otherwise every rule is vacuous")
                .isNotEmpty();

        List<String> uncoveredButNotPending = new ArrayList<>();
        List<String> stalePendingMarkers = new ArrayList<>();
        List<String> pendingReport = new ArrayList<>();

        for (ArchitectureRules.NamedRule named : ArchitectureRules.all()) {
            boolean coversSomething = matchesAnyClass(production, named.targetPackage());

            if (named.isPending()) {
                pendingReport.add("%s (%s) - pending until slice %s"
                        .formatted(named.id(), named.description(), named.pendingUntilSlice()));
                if (coversSomething) {
                    stalePendingMarkers.add("%s now covers real classes in %s; remove its "
                            + "pendingUntilSlice marker so it is enforced"
                            .formatted(named.id(), named.targetPackage()));
                }
            } else if (!coversSomething) {
                uncoveredButNotPending.add("%s (%s) matched no class in %s"
                        .formatted(named.id(), named.description(), named.targetPackage()));
            }
        }

        // Printed so a pending rule is visible in CI output rather than invisible.
        pendingReport.forEach(line -> System.out.println("[arch pending] " + line));

        assertThat(uncoveredButNotPending)
                .as("these rules matched nothing and are not declared pending, so they "
                        + "are silently green")
                .isEmpty();
        assertThat(stalePendingMarkers)
                .as("these rules are still marked pending but their packages now exist")
                .isEmpty();
    }

    @Test
    @DisplayName("the packages the AI boundary protects are declared, even before they exist")
    void aiBoundaryRulesAreDeclaredForEveryProtectedPackage() {
        List<String> protectedPackages = ArchitectureRules.all().stream()
                .filter(r -> List.of("R1", "R2", "R3").contains(r.id()))
                .map(ArchitectureRules.NamedRule::targetPackage)
                .toList();

        assertThat(protectedPackages)
                .as("scheduling, grading and mastery must each have a rule keeping them "
                        + "away from the AI platform")
                .containsExactlyInAnyOrder(
                        "..planning.internal.scheduler..",
                        "..assessment.internal.grading..",
                        "..assessment.internal.mastery..");
    }

    private static boolean matchesAnyClass(JavaClasses classes, String packagePattern) {
        DescribedPredicate<String> matcher = PackageMatchers.of(packagePattern);
        for (JavaClass javaClass : classes) {
            if (matcher.test(javaClass.getPackageName())) {
                return true;
            }
        }
        return false;
    }
}
