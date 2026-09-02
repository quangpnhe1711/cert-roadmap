package com.certcopilot.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides whether a missing database skips or fails.
 *
 * <p>Worth its own test because it is the one piece of test infrastructure whose
 * failure mode is a <em>green</em> build - a verification run that skipped every
 * test touching a database and reported success. Nothing downstream would notice.
 */
class CiVerificationModeTest {

    @Test
    @DisplayName("with a database, tests run in both modes")
    void runsWhenTheDatabaseIsThere() {
        assertThat(PostgresSupport.decide(true, false)).isEqualTo(PostgresSupport.Action.RUN);
        assertThat(PostgresSupport.decide(true, true)).isEqualTo(PostgresSupport.Action.RUN);
    }

    @Test
    @DisplayName("locally, a missing database skips so unit and architecture tests still give feedback")
    void skipsLocally() {
        assertThat(PostgresSupport.decide(false, false)).isEqualTo(PostgresSupport.Action.SKIP);
    }

    @Test
    @DisplayName("in CI, a missing database fails the build instead of quietly certifying nothing")
    void failsInCi() {
        assertThat(PostgresSupport.decide(false, true)).isEqualTo(PostgresSupport.Action.FAIL);
    }
}
