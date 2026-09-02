package com.certcopilot.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for tests that need a real PostgreSQL, because the job queue relies
 * on {@code FOR UPDATE SKIP LOCKED} and the budget reservation relies on row-level
 * update serialisation. Neither can be verified against an in-memory database, so
 * mocking here would prove nothing.
 *
 * <p>Two ways to supply the database, checked in order:
 *
 * <ol>
 *   <li>{@code CERTCOPILOT_TEST_DB_URL} (plus user and password) points at any
 *       reachable PostgreSQL - useful on a machine with a native install and no
 *       Docker daemon running;
 *   <li>otherwise Testcontainers starts one, which needs a running Docker daemon.
 * </ol>
 *
 * <p>When neither is available the tests are skipped rather than failed, so a
 * developer without Docker still gets a green build from the unit and
 * architecture suites.
 *
 * <p><b>Except in CI.</b> Run with {@code -Dci.verification=true} (or with the
 * {@code CI} environment variable set) and a missing database is a build failure
 * instead. A verification run that reports success because it quietly skipped
 * every test that touches a database is worse than a red build: it certifies
 * nothing while looking exactly like a run that certified everything.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(SpringExtension.class)
@Testcontainers
public abstract class PostgresSupport {

    private static final String ENV_URL = "CERTCOPILOT_TEST_DB_URL";
    private static final String ENV_USER = "CERTCOPILOT_TEST_DB_USER";
    private static final String ENV_PASSWORD = "CERTCOPILOT_TEST_DB_PASSWORD";

    private static PostgreSQLContainer<?> container;

    static boolean externalDatabaseConfigured() {
        return System.getenv(ENV_URL) != null && !System.getenv(ENV_URL).isBlank();
    }

    static boolean dockerAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when some database is reachable, by either route. */
    public static boolean databaseAvailable() {
        return externalDatabaseConfigured() || dockerAvailable();
    }

    /**
     * True when this run is meant to certify the build rather than to give a
     * developer fast feedback.
     */
    public static boolean ciVerification() {
        return Boolean.parseBoolean(System.getProperty("ci.verification", "false"))
                || Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false"));
    }

    /**
     * Call from {@code @BeforeEach} in place of a bare assumption.
     *
     * <p>Skips locally, fails in CI. Every integration test in this project needs
     * a real PostgreSQL - {@code FOR UPDATE SKIP LOCKED} and row-level update
     * serialisation cannot be faked - so "no database" means "not verified", and
     * only one of those two words belongs in a green build.
     */
    public static void requireDatabase() {
        switch (decide(databaseAvailable(), ciVerification())) {
            case RUN -> { }
            case FAIL -> throw new AssertionError(
                    "CI verification requires a PostgreSQL and none is reachable. "
                            + "Start Docker, or set " + ENV_URL + ". Skipping these tests would "
                            + "report a passing build that verified nothing.");
            case SKIP -> org.junit.jupiter.api.Assumptions.abort(
                    "no PostgreSQL available (set " + ENV_URL + " or start Docker); "
                            + "run with -Dci.verification=true to make this a failure");
        }
    }

    enum Action { RUN, SKIP, FAIL }

    /**
     * The whole rule, as a pure function so it can be tested without taking a
     * database away from the machine running the tests.
     */
    static Action decide(boolean databaseAvailable, boolean ciVerification) {
        if (databaseAvailable) {
            return Action.RUN;
        }
        return ciVerification ? Action.FAIL : Action.SKIP;
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        if (externalDatabaseConfigured()) {
            registry.add("spring.datasource.url", () -> System.getenv(ENV_URL));
            registry.add("spring.datasource.username",
                    () -> System.getenv(ENV_USER) == null ? "postgres" : System.getenv(ENV_USER));
            registry.add("spring.datasource.password",
                    () -> System.getenv(ENV_PASSWORD) == null ? "" : System.getenv(ENV_PASSWORD));
            return;
        }
        if (dockerAvailable()) {
            if (container == null) {
                container = new PostgreSQLContainer<>("postgres:16-alpine")
                        .withDatabaseName("certcopilot")
                        .withUsername("certcopilot")
                        .withPassword("certcopilot");
                container.start();
            }
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
    }
}
