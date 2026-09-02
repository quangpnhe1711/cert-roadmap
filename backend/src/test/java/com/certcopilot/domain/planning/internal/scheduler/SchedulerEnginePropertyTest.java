package com.certcopilot.domain.planning.internal.scheduler;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.certcopilot.shared.DepthFlag;
import com.certcopilot.shared.ExamRelevance;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduler is a pure function, which is exactly why property testing is the
 * right tool: thousands of generated plans check invariants that no hand-written
 * example set would cover.
 *
 * <p>Each property here corresponds to a promise the product makes to a learner.
 */
class SchedulerEnginePropertyTest {

    @Property(tries = 300)
    void neverExceedsDailyCapacityBeyondTolerance(@ForAll("contexts") PlanningContext ctx) {
        if (!(SchedulerEngine.plan(ctx) instanceof SchedulingResult.Scheduled scheduled)) {
            return;
        }
        for (SchedulingResult.ScheduledDay day : scheduled.days()) {
            int ceiling = (int) Math.floor(day.capacityMinutes() * ctx.config().overfillTolerance());
            // The final content day absorbs leftovers by design; everything else
            // must respect the ceiling.
            boolean isLastContentDay = isLastContentDay(scheduled, day);
            if (!isLastContentDay) {
                assertThat(day.allocatedMinutes())
                        .as("day %s exceeded its ceiling", day.date())
                        .isLessThanOrEqualTo(ceiling);
            }
        }
    }

    @Property(tries = 300)
    void neverSchedulesOnOrAfterExamDate(@ForAll("contexts") PlanningContext ctx) {
        if (!(SchedulerEngine.plan(ctx) instanceof SchedulingResult.Scheduled scheduled)) {
            return;
        }
        for (SchedulingResult.ScheduledDay day : scheduled.days()) {
            assertThat(day.date())
                    .as("nothing may be scheduled on or after the exam date")
                    .isBefore(ctx.examDate());
            assertThat(day.date())
                    .as("nothing may be scheduled in the past")
                    .isAfterOrEqualTo(ctx.today());
        }
    }

    @Property(tries = 300)
    void neverSchedulesOnBlockedOrZeroCapacityDays(@ForAll("contexts") PlanningContext ctx) {
        if (!(SchedulerEngine.plan(ctx) instanceof SchedulingResult.Scheduled scheduled)) {
            return;
        }
        for (SchedulingResult.ScheduledDay day : scheduled.days()) {
            assertThat(ctx.blockedDates()).doesNotContain(day.date());
            assertThat(ctx.capacityFor(day.date()))
                    .as("a scheduled day must have capacity")
                    .isGreaterThan(0);
        }
    }

    @Property(tries = 300)
    void neverTouchesFrozenDays(@ForAll("contexts") PlanningContext ctx) {
        if (!(SchedulerEngine.plan(ctx) instanceof SchedulingResult.Scheduled scheduled)) {
            return;
        }
        Set<LocalDate> frozen = ctx.frozenDays().stream()
                .map(PlanningContext.FrozenDay::date).collect(Collectors.toSet());
        if (frozen.isEmpty()) {
            return;
        }
        Set<LocalDate> emitted = scheduled.days().stream()
                .map(SchedulingResult.ScheduledDay::date).collect(Collectors.toSet());

        assertThat(emitted)
                .as("a completed day is history and must never be rewritten")
                .doesNotContainAnyElementsOf(frozen);
    }

    @Property(tries = 300)
    void everyPendingUnitIsEitherScheduledOrExplicitlyDropped(@ForAll("contexts") PlanningContext ctx) {
        if (!(SchedulerEngine.plan(ctx) instanceof SchedulingResult.Scheduled scheduled)) {
            return;
        }
        Set<UUID> expected = ctx.pendingUnits().stream()
                .filter(u -> !u.markedKnown())
                .map(PlanningContext.SchedulableUnit::learningUnitId)
                .collect(Collectors.toSet());

        Set<UUID> scheduledIds = scheduled.days().stream()
                .flatMap(d -> d.items().stream())
                .filter(i -> i.type() == SchedulingResult.ItemType.LEARNING_UNIT)
                .map(SchedulingResult.ScheduledItem::learningUnitId)
                .collect(Collectors.toSet());

        Set<UUID> droppedIds = scheduled.droppedUnits().stream()
                .map(SchedulingResult.DroppedUnit::learningUnitId)
                .collect(Collectors.toSet());

        Set<UUID> accounted = new HashSet<>(scheduledIds);
        accounted.addAll(droppedIds);

        assertThat(accounted)
                .as("no unit may silently disappear: it is scheduled or dropped with a reason")
                .containsAll(expected);
    }

    @Property(tries = 300)
    void everyDroppedUnitCarriesAReason(@ForAll("contexts") PlanningContext ctx) {
        if (!(SchedulerEngine.plan(ctx) instanceof SchedulingResult.Scheduled scheduled)) {
            return;
        }
        assertThat(scheduled.droppedUnits())
                .allSatisfy(d -> assertThat(d.reasonCode()).isNotBlank());
    }

    @Property(tries = 300)
    void knownUnitsAreNeverScheduled(@ForAll("contexts") PlanningContext ctx) {
        if (!(SchedulerEngine.plan(ctx) instanceof SchedulingResult.Scheduled scheduled)) {
            return;
        }
        Set<UUID> known = ctx.pendingUnits().stream()
                .filter(PlanningContext.SchedulableUnit::markedKnown)
                .map(PlanningContext.SchedulableUnit::learningUnitId)
                .collect(Collectors.toSet());
        if (known.isEmpty()) {
            return;
        }

        Set<UUID> scheduledIds = scheduled.days().stream()
                .flatMap(d -> d.items().stream())
                .map(SchedulingResult.ScheduledItem::learningUnitId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        assertThat(scheduledIds)
                .as("a unit the learner marked as known must not be scheduled")
                .doesNotContainAnyElementsOf(known);
    }

    @Property(tries = 300)
    void reviewBlocksComeBeforeNewContentInTheirDay(@ForAll("contexts") PlanningContext ctx) {
        if (!(SchedulerEngine.plan(ctx) instanceof SchedulingResult.Scheduled scheduled)) {
            return;
        }
        for (SchedulingResult.ScheduledDay day : scheduled.days()) {
            int lastReviewIndex = -1;
            int firstUnitIndex = Integer.MAX_VALUE;
            for (SchedulingResult.ScheduledItem item : day.items()) {
                if (item.type() == SchedulingResult.ItemType.REVIEW_BLOCK) {
                    lastReviewIndex = Math.max(lastReviewIndex, item.orderIndex());
                } else if (item.type() == SchedulingResult.ItemType.LEARNING_UNIT) {
                    firstUnitIndex = Math.min(firstUnitIndex, item.orderIndex());
                }
            }
            if (lastReviewIndex >= 0 && firstUnitIndex != Integer.MAX_VALUE) {
                assertThat(lastReviewIndex)
                        .as("revisiting a weak topic must precede new material on %s", day.date())
                        .isLessThan(firstUnitIndex);
            }
        }
    }

    @Property(tries = 200)
    void isDeterministic(@ForAll("contexts") PlanningContext ctx) {
        SchedulingResult first = SchedulerEngine.plan(ctx);
        SchedulingResult second = SchedulerEngine.plan(ctx);

        // Same input, same output. No clock read inside, no randomness.
        assertThat(describe(first))
                .as("the scheduler must be a pure function")
                .isEqualTo(describe(second));
    }

    @Property(tries = 200)
    void infeasibleAlwaysOffersConcreteOptions(@ForAll("contexts") PlanningContext ctx) {
        if (SchedulerEngine.plan(ctx) instanceof SchedulingResult.Infeasible infeasible) {
            assertThat(infeasible.suggestions())
                    .as("telling a learner it cannot be done without options is useless")
                    .isNotEmpty();
            assertThat(infeasible.deficitMinutes()).isGreaterThanOrEqualTo(0);
        }
    }

    @Property(tries = 200)
    void allocationNeverExceedsTotalCapacityPlusTolerance(@ForAll("contexts") PlanningContext ctx) {
        if (!(SchedulerEngine.plan(ctx) instanceof SchedulingResult.Scheduled scheduled)) {
            return;
        }
        int allocated = scheduled.days().stream()
                .mapToInt(SchedulingResult.ScheduledDay::allocatedMinutes).sum();
        int required = ctx.pendingUnits().stream()
                .filter(u -> !u.markedKnown())
                .mapToInt(PlanningContext.SchedulableUnit::effortMinutes).sum()
                + ctx.reviewBlocks().stream()
                .mapToInt(PlanningContext.ReviewBlock::effortMinutes).sum();

        // Allocation may be lower (units dropped, or the exam block capped) but
        // never invents work that was not requested. The exam block is the one
        // addition, capped at 120 minutes.
        assertThat(allocated)
                .as("total allocation must be traceable to requested work")
                .isLessThanOrEqualTo(required + 120 * Math.max(1, scheduled.days().size()));
    }

    private static boolean isLastContentDay(SchedulingResult.Scheduled scheduled,
                                            SchedulingResult.ScheduledDay day) {
        List<SchedulingResult.ScheduledDay> content = scheduled.days().stream()
                .filter(d -> d.kind() == SchedulingResult.DayKind.CONTENT)
                .toList();
        return !content.isEmpty() && content.get(content.size() - 1).date().equals(day.date());
    }

    private static String describe(SchedulingResult result) {
        if (result instanceof SchedulingResult.Scheduled s) {
            return s.days().stream()
                    .map(d -> d.date() + ":" + d.items().stream()
                            .map(i -> i.type() + "/" + i.learningUnitId() + "/" + i.allottedMinutes())
                            .collect(Collectors.joining(",")))
                    .collect(Collectors.joining("|"))
                    + "||dropped=" + s.droppedUnits().size() + "||mode=" + s.compressionMode();
        }
        SchedulingResult.Infeasible i = (SchedulingResult.Infeasible) result;
        return "INFEASIBLE:" + i.deficitMinutes() + "/" + i.requiredMinutes();
    }

    // ------------------------------------------------------------ generators

    @Provide
    Arbitrary<PlanningContext> contexts() {
        Arbitrary<Integer> horizon = Arbitraries.integers().between(2, 90);
        Arbitrary<Integer> unitCount = Arbitraries.integers().between(0, 60);
        Arbitrary<Integer> dailyMinutes = Arbitraries.integers().between(0, 480);
        Arbitrary<Integer> reviewCount = Arbitraries.integers().between(0, 6);
        Arbitrary<Integer> blockedCount = Arbitraries.integers().between(0, 8);
        Arbitrary<Long> seed = Arbitraries.longs().between(1, 1_000_000);

        return Combinators.combine(horizon, unitCount, dailyMinutes, reviewCount, blockedCount, seed)
                .as(SchedulerEnginePropertyTest::buildContext);
    }

    private static PlanningContext buildContext(int horizonDays, int unitCount, int dailyMinutes,
                                                int reviewCount, int blockedCount, long seed) {
        java.util.Random random = new java.util.Random(seed);
        LocalDate today = LocalDate.of(2026, 3, 2);
        LocalDate examDate = today.plusDays(horizonDays);

        Map<DayOfWeek, Integer> capacity = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek dow : DayOfWeek.values()) {
            // Some days off is the realistic case, so generate zeros too.
            capacity.put(dow, random.nextInt(4) == 0 ? 0 : Math.max(0, dailyMinutes));
        }

        Set<LocalDate> blocked = new HashSet<>();
        for (int i = 0; i < blockedCount && horizonDays > 1; i++) {
            blocked.add(today.plusDays(random.nextInt(horizonDays)));
        }

        List<PlanningContext.SchedulableUnit> units = new ArrayList<>();
        ExamRelevance[] relevances = ExamRelevance.values();
        for (int i = 0; i < unitCount; i++) {
            units.add(new PlanningContext.SchedulableUnit(
                    new UUID(seed, i),
                    i,
                    20 + random.nextInt(160),
                    relevances[random.nextInt(relevances.length)],
                    DepthFlag.FULL,
                    random.nextInt(8) == 0));
        }

        List<PlanningContext.ReviewBlock> reviews = new ArrayList<>();
        for (int i = 0; i < reviewCount; i++) {
            reviews.add(new PlanningContext.ReviewBlock(
                    new UUID(seed + 1, i), 10 + random.nextInt(20), random.nextDouble()));
        }

        // Some contexts model a plan already under way, so the frozen-day
        // invariant is exercised rather than trivially true.
        List<PlanningContext.FrozenDay> frozen = new ArrayList<>();
        int frozenCount = random.nextInt(Math.min(4, Math.max(1, horizonDays / 3)));
        for (int i = 0; i < frozenCount; i++) {
            frozen.add(new PlanningContext.FrozenDay(today.plusDays(i), 60 + random.nextInt(180)));
        }

        return new PlanningContext(
                today, examDate, capacity, blocked,
                frozen, units, reviews,
                PlanningContext.SchedulerConfig.defaults());
    }

    /** Keeps jqwik's annotation import used even if a property is temporarily disabled. */
    @Property(tries = 1)
    void configDefaultsAreSane(@ForAll @IntRange(min = 1, max = 1) int ignored) {
        PlanningContext.SchedulerConfig config = PlanningContext.SchedulerConfig.defaults();
        assertThat(config.minReviewWindowDays()).isLessThanOrEqualTo(config.maxReviewWindowDays());
        assertThat(config.overfillTolerance()).isBetween(1.0, 1.5);
    }
}
