package com.certcopilot.platform.ai;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

/**
 * Correction A3: per-plan AI budget with atomic reservation.
 *
 * <p>Two concurrent workers must never be able to overspend the same remaining
 * budget. The reservation is a single guarded {@code UPDATE}: PostgreSQL
 * serialises updates to the same row, so the second worker reads the already
 * incremented {@code reserved_cents} and is refused. There is no read-then-write
 * window to lose a race in.
 *
 * <p>A reservation carries an expiry so a worker that dies between reserving and
 * settling cannot strand budget forever; {@link #sweepExpiredReservations()}
 * releases those.
 */
@Component
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final JdbcTemplate jdbc;

    public BudgetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the budget row for a plan if it does not exist yet. */
    public void ensureBudget(UUID planId, int hardCapCents, int softCapCents) {
        jdbc.update(
                "INSERT INTO plan_budget (plan_id, hard_cap_cents, soft_cap_cents) "
                        + "VALUES (?, ?, ?) ON CONFLICT (plan_id) DO NOTHING",
                planId, hardCapCents, softCapCents);
    }

    /**
     * Atomically reserves {@code estimatedCents} against the plan's hard cap.
     *
     * @return the reservation id, or {@code null} when the hard cap would be exceeded
     */
    // Committed on its own so a reservation cannot silently vanish with the
    // caller's transaction while the provider call it authorised still happens.
    // A reservation orphaned by a crash is reclaimed by its TTL instead.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID reserve(UUID planId, String operationId, int estimatedCents, Duration ttl) {
        int updated = jdbc.update(
                "UPDATE plan_budget "
                        + "   SET reserved_cents = reserved_cents + ?, updated_at = now() "
                        + " WHERE plan_id = ? "
                        + "   AND settled_cents + reserved_cents + ? <= hard_cap_cents",
                estimatedCents, planId, estimatedCents);

        if (updated == 0) {
            log.warn("budget denied for plan={} operation={} estimate={}c",
                    planId, operationId, estimatedCents);
            return null;
        }

        UUID reservationId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO budget_reservation "
                        + "(id, plan_id, operation_id, estimated_cents, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                reservationId, planId, operationId, estimatedCents,
                Timestamp.from(Instant.now().plus(ttl)));
        return reservationId;
    }

    /**
     * Converts a reservation into actual spend. Called for every provider attempt,
     * including attempts later rejected by parse or validation.
     *
     * <p>Committed independently of the caller, for the same reason the ledger is:
     * un-settling real spend when a business transaction fails leaves the cap
     * believing the money is still available.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(UUID reservationId, UUID planId, int estimatedCents, int actualCents) {
        jdbc.update(
                "UPDATE plan_budget "
                        + "   SET reserved_cents = GREATEST(0, reserved_cents - ?), "
                        + "       settled_cents = settled_cents + ?, "
                        + "       soft_cap_breached_at = CASE "
                        + "           WHEN soft_cap_breached_at IS NULL "
                        + "            AND settled_cents + ? > soft_cap_cents THEN now() "
                        + "           ELSE soft_cap_breached_at END, "
                        + "       updated_at = now() "
                        + " WHERE plan_id = ?",
                estimatedCents, actualCents, actualCents, planId);
        jdbc.update("UPDATE budget_reservation SET settled_at = now() WHERE id = ?", reservationId);
    }

    /** Releases a reservation without spending, for example when the call never happened. */
    public void release(UUID reservationId, UUID planId, int estimatedCents) {
        settle(reservationId, planId, estimatedCents, 0);
    }

    /**
     * Releases reservations whose worker died before settling. Without this a
     * crash slowly eats the plan budget with money that was never spent.
     *
     * @return number of reservations released
     */
    public int sweepExpiredReservations() {
        var expired = jdbc.queryForList(
                "SELECT id, plan_id, estimated_cents FROM budget_reservation "
                        + " WHERE settled_at IS NULL AND expires_at < now()");
        for (var row : expired) {
            UUID id = (UUID) row.get("id");
            UUID planId = (UUID) row.get("plan_id");
            int cents = ((Number) row.get("estimated_cents")).intValue();
            release(id, planId, cents);
            log.warn("released expired budget reservation {} for plan {} ({}c)", id, planId, cents);
        }
        return expired.size();
    }

    public Snapshot snapshot(UUID planId) {
        return jdbc.queryForObject(
                "SELECT hard_cap_cents, soft_cap_cents, settled_cents, reserved_cents "
                        + "  FROM plan_budget WHERE plan_id = ?",
                (rs, n) -> new Snapshot(
                        rs.getInt("hard_cap_cents"),
                        rs.getInt("soft_cap_cents"),
                        rs.getInt("settled_cents"),
                        rs.getInt("reserved_cents")),
                planId);
    }

    public record Snapshot(int hardCapCents, int softCapCents, int settledCents, int reservedCents) {
        @com.fasterxml.jackson.annotation.JsonProperty("softCapBreached")
        public boolean softCapBreached() {
            return settledCents > softCapCents;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("availableCents")
        public int availableCents() {
            return hardCapCents - settledCents - reservedCents;
        }
    }
}
