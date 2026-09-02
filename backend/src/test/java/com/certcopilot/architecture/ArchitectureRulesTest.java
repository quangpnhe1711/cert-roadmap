package com.certcopilot.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the production architecture rules against production classes.
 *
 * <p>Fixtures under {@code archfixture} are test classes and are excluded by
 * {@code DoNotIncludeTests}, so the deliberately violating fixtures cannot break
 * this suite.
 */
class ArchitectureRulesTest {

    private static JavaClasses production;

    @BeforeAll
    static void importClasses() {
        production = ArchitectureRules.productionClasses();
    }

    @Test
    @DisplayName("all architecture rules hold for production code")
    void allRulesHold() {
        for (ArchitectureRules.NamedRule named : ArchitectureRules.all()) {
            named.rule().check(production);
        }
    }
}
