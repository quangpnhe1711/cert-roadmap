package com.certcopilot.platform.ai;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.certcopilot.domain.identity.AuthService;
import com.certcopilot.domain.planning.PlanService;
import com.certcopilot.support.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correction A3: concurrent workers must not be able to overspend the same
 * remaining plan budget.
 *
 * <p>This is the test the correction asked for. It is deliberately run against a
 * real database, because the guarantee comes from PostgreSQL serialising updates
 * to one row - not from anything in the Java code.
 */
class BudgetConcurrencyIT extends PostgresSupport {

    private static final UUID AIF_C01 = UUID.fromString("a1f00000-0000-4000-8000-000000000002");

    @Autowired
    private BudgetService budget;

    @Autowired
    private AuthService auth;

    @Autowired
    private PlanService plans;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID planId;

    @BeforeEach
    void setUp() {
        requireDatabase();
        // plan_budget is keyed to a real plan, so the budget cannot outlive it.
        UUID userId = auth.register(
                "budget-" + UUID.randomUUID() + "@example.com", "correct-horse-battery", "Budget")
                .userId();
        java.util.Map<String, Integer> capacity = new java.util.LinkedHashMap<>();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            capacity.put(day.name(), 120);
        }
        planId = plans.createDraft(userId, AIF_C01,
                java.time.LocalDate.now().plusDays(30), capacity, List.of(), null);
        // createDraft seeds a default budget; these tests set their own caps.
        jdbc.update("DELETE FROM budget_reservation WHERE plan_id = ?", planId);
        jdbc.update("DELETE FROM plan_budget WHERE plan_id = ?", planId);
    }

    @Test
    @DisplayName("parallel reservations never exceed the hard cap")
    void parallelReservationsRespectHardCap() throws Exception {
        int hardCap = 100;
        int perCall = 10;
        int attempts = 40;
        budget.ensureBudget(planId, hardCap, 50);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<UUID>> tasks = IntStream.range(0, attempts)
                .mapToObj(i -> (Callable<UUID>) () ->
                        budget.reserve(planId, "op.test", perCall, Duration.ofMinutes(5)))
                .collect(Collectors.toList());

        long granted = pool.invokeAll(tasks).stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .filter(java.util.Objects::nonNull)
                .count();
        pool.shutdown();

        assertThat(granted)
                .as("exactly hardCap/perCall reservations may be granted, no more")
                .isEqualTo(hardCap / perCall);

        BudgetService.Snapshot snapshot = budget.snapshot(planId);
        assertThat(snapshot.reservedCents() + snapshot.settledCents())
                .as("committed spend must never exceed the hard cap")
                .isLessThanOrEqualTo(hardCap);
    }

    @Test
    @DisplayName("settling converts a reservation into actual spend")
    void settleMovesReservedToSettled() {
        budget.ensureBudget(planId, 1000, 500);
        UUID reservation = budget.reserve(planId, "op.test", 50, Duration.ofMinutes(5));
        assertThat(reservation).isNotNull();

        assertThat(budget.snapshot(planId).reservedCents()).isEqualTo(50);

        budget.settle(reservation, planId, 50, 37);

        BudgetService.Snapshot after = budget.snapshot(planId);
        assertThat(after.reservedCents()).isZero();
        assertThat(after.settledCents())
                .as("actual cost is settled, not the estimate")
                .isEqualTo(37);
    }

    @Test
    @DisplayName("an expired reservation is released so a dead worker cannot leak budget")
    void expiredReservationIsSwept() {
        budget.ensureBudget(planId, 1000, 500);
        UUID reservation = budget.reserve(planId, "op.test", 200, Duration.ofMinutes(5));
        assertThat(budget.snapshot(planId).reservedCents()).isEqualTo(200);

        // Simulate the worker dying between reserving and settling.
        jdbc.update("UPDATE budget_reservation SET expires_at = now() - interval '1 minute' "
                + " WHERE id = ?", reservation);

        assertThat(budget.sweepExpiredReservations()).isEqualTo(1);

        BudgetService.Snapshot after = budget.snapshot(planId);
        assertThat(after.reservedCents())
                .as("the stranded reservation must be released")
                .isZero();
        assertThat(after.settledCents())
                .as("no money was actually spent")
                .isZero();
    }

    @Test
    @DisplayName("a reservation is refused once the hard cap is committed")
    void reservationRefusedAtHardCap() {
        budget.ensureBudget(planId, 100, 50);
        assertThat(budget.reserve(planId, "op.test", 100, Duration.ofMinutes(5))).isNotNull();
        assertThat(budget.reserve(planId, "op.test", 1, Duration.ofMinutes(5)))
                .as("nothing may be reserved beyond the hard cap")
                .isNull();
    }
}
