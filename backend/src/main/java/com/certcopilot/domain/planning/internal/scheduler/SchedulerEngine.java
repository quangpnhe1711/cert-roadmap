package com.certcopilot.domain.planning.internal.scheduler;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.certcopilot.shared.DepthFlag;
import com.certcopilot.shared.ExamRelevance;

/**
 * The deterministic core of the product.
 *
 * <p>Pure by construction: no database, no network, no model, no clock, no
 * mutable global state. Everything time-related arrives in the
 * {@link PlanningContext}. That is what lets the same function serve all four
 * replan triggers, lets property tests generate thousands of inputs, and makes a
 * schedule reproducible when a learner asks why their plan changed.
 *
 * <p>Architecture rule R1 forbids this package from depending on
 * {@code platform.ai}. A model may rate difficulty; it never decides a date.
 */
public final class SchedulerEngine {

    private SchedulerEngine() {
    }

    public static SchedulingResult plan(PlanningContext ctx) {
        List<LocalDate> available = availableDays(ctx);
        if (available.isEmpty()) {
            return infeasible(ctx, 0, requiredMinutes(ctx.pendingUnits(), ctx.reviewBlocks()));
        }

        int reviewWindowSize = reviewWindowSize(available.size(), ctx.config());
        List<LocalDate> contentDays = available.subList(0, available.size() - reviewWindowSize);
        List<LocalDate> reviewDays = available.subList(available.size() - reviewWindowSize, available.size());

        // A plan with no content days left is still valid: everything remaining
        // becomes review. Better an honest short plan than a fabricated one.
        if (contentDays.isEmpty()) {
            contentDays = available.subList(0, Math.max(0, available.size() - 1));
            reviewDays = available.subList(contentDays.size(), available.size());
        }

        List<PlanningContext.SchedulableUnit> candidates = ctx.pendingUnits().stream()
                .filter(u -> !u.markedKnown())
                .sorted(Comparator.comparingInt(PlanningContext.SchedulableUnit::orderIndex))
                .toList();

        int contentCapacity = contentDays.stream().mapToInt(ctx::capacityFor).sum();

        Compression compression = applyCompressionLadder(
                candidates, ctx.reviewBlocks(), contentCapacity, ctx.config());

        if (compression.stillOverCapacity()) {
            return infeasible(ctx, contentCapacity, compression.requiredMinutes());
        }

        List<SchedulingResult.ScheduledDay> days = new ArrayList<>();
        List<SchedulingResult.Adjustment> adjustments = new ArrayList<>(compression.adjustments());

        packContentDays(ctx, contentDays, compression, days);
        packReviewDays(ctx, reviewDays, days, contentDays.size());

        double effortRatio = contentCapacity == 0
                ? Double.POSITIVE_INFINITY
                : (double) compression.requiredMinutes() / contentCapacity;

        if (!compression.dropped().isEmpty()) {
            adjustments.add(new SchedulingResult.Adjustment(
                    "UNITS_DROPPED_FOR_CAPACITY",
                    compression.dropped().size() + " unit(s) removed to fit the remaining time"));
        }

        return new SchedulingResult.Scheduled(
                List.copyOf(days),
                compression.dropped(),
                List.copyOf(adjustments),
                compression.mode(),
                effortRatio);
    }

    // ------------------------------------------------------------------ days

    /** Days from today up to but excluding the exam date, minus blocked and zero-capacity days. */
    static List<LocalDate> availableDays(PlanningContext ctx) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = ctx.today(); d.isBefore(ctx.examDate()); d = d.plusDays(1)) {
            if (ctx.capacityFor(d) > 0 && !isFrozen(ctx, d)) {
                days.add(d);
            }
        }
        return days;
    }

    private static boolean isFrozen(PlanningContext ctx, LocalDate date) {
        return ctx.frozenDays().stream().anyMatch(f -> f.date().equals(date));
    }

    static int reviewWindowSize(int totalDays, PlanningContext.SchedulerConfig config) {
        int desired = (int) Math.round(totalDays * config.reviewWindowRatio());
        int clamped = Math.max(config.minReviewWindowDays(),
                Math.min(config.maxReviewWindowDays(), desired));
        // Never reserve so much that there is nothing left to learn.
        return Math.min(clamped, Math.max(0, totalDays - 1));
    }

    // ----------------------------------------------------------- compression

    /**
     * The compression ladder. Each rung sheds less-relevant work before touching
     * anything that matters, and the last rung refuses rather than pretending.
     */
    private static Compression applyCompressionLadder(
            List<PlanningContext.SchedulableUnit> candidates,
            List<PlanningContext.ReviewBlock> reviewBlocks,
            int contentCapacity,
            PlanningContext.SchedulerConfig config) {

        List<PlanningContext.SchedulableUnit> kept = new ArrayList<>(candidates);
        List<SchedulingResult.DroppedUnit> dropped = new ArrayList<>();
        List<SchedulingResult.Adjustment> adjustments = new ArrayList<>();
        String mode = "NONE";

        int reviewMinutes = reviewBlocks.stream()
                .mapToInt(PlanningContext.ReviewBlock::effortMinutes).sum();

        if (total(kept) + reviewMinutes <= contentCapacity) {
            return new Compression(kept, dropped, adjustments, mode,
                    total(kept) + reviewMinutes, contentCapacity);
        }

        // L1 drop OPTIONAL
        mode = "DROP_OPTIONAL";
        dropByRelevance(kept, dropped, ExamRelevance.OPTIONAL, "DROPPED_OPTIONAL");
        adjustments.add(new SchedulingResult.Adjustment("COMPRESSION_L1",
                "removed content outside the exam scope"));
        if (total(kept) + reviewMinutes <= contentCapacity) {
            return new Compression(kept, dropped, adjustments, mode,
                    total(kept) + reviewMinutes, contentCapacity);
        }

        // L2 drop LOW
        mode = "DROP_LOW";
        dropByRelevance(kept, dropped, ExamRelevance.LOW, "DROPPED_LOW_RELEVANCE");
        adjustments.add(new SchedulingResult.Adjustment("COMPRESSION_L2",
                "removed low-relevance content"));
        if (total(kept) + reviewMinutes <= contentCapacity) {
            return new Compression(kept, dropped, adjustments, mode,
                    total(kept) + reviewMinutes, contentCapacity);
        }

        // L3 condense MEDIUM: shorter lessons and fewer questions, not removal.
        mode = "CONDENSE_MEDIUM";
        List<PlanningContext.SchedulableUnit> condensed = new ArrayList<>();
        for (PlanningContext.SchedulableUnit unit : kept) {
            if (unit.relevance() == ExamRelevance.MEDIUM && unit.depthFlag() == DepthFlag.FULL) {
                condensed.add(new PlanningContext.SchedulableUnit(
                        unit.learningUnitId(), unit.orderIndex(),
                        Math.max(config.minUnitSplitMinutes(),
                                (int) Math.round(unit.effortMinutes() * DepthFlag.CONDENSED.effortMultiplier())),
                        unit.relevance(), DepthFlag.CONDENSED, unit.markedKnown()));
            } else {
                condensed.add(unit);
            }
        }
        kept = condensed;
        adjustments.add(new SchedulingResult.Adjustment("COMPRESSION_L3",
                "shortened medium-relevance lessons"));

        return new Compression(kept, dropped, adjustments, mode,
                total(kept) + reviewMinutes, contentCapacity);
    }

    private static void dropByRelevance(List<PlanningContext.SchedulableUnit> kept,
                                        List<SchedulingResult.DroppedUnit> dropped,
                                        ExamRelevance relevance,
                                        String reasonCode) {
        kept.removeIf(unit -> {
            if (unit.relevance() == relevance) {
                dropped.add(new SchedulingResult.DroppedUnit(unit.learningUnitId(), reasonCode));
                return true;
            }
            return false;
        });
    }

    private static int total(List<PlanningContext.SchedulableUnit> units) {
        return units.stream().mapToInt(PlanningContext.SchedulableUnit::effortMinutes).sum();
    }

    private static int requiredMinutes(List<PlanningContext.SchedulableUnit> units,
                                       List<PlanningContext.ReviewBlock> reviews) {
        return units.stream().filter(u -> !u.markedKnown())
                .mapToInt(PlanningContext.SchedulableUnit::effortMinutes).sum()
                + reviews.stream().mapToInt(PlanningContext.ReviewBlock::effortMinutes).sum();
    }

    // --------------------------------------------------------------- packing

    private static void packContentDays(PlanningContext ctx,
                                        List<LocalDate> contentDays,
                                        Compression compression,
                                        List<SchedulingResult.ScheduledDay> out) {

        // Review blocks first, hardest weakness first, so shaky topics are
        // revisited before new material lands on top of them.
        List<PlanningContext.ReviewBlock> reviews = new ArrayList<>(ctx.reviewBlocks());
        reviews.sort(Comparator.comparingDouble(PlanningContext.ReviewBlock::weaknessScore).reversed());

        java.util.Deque<PlanningContext.SchedulableUnit> queue =
                new java.util.ArrayDeque<>(compression.kept());
        java.util.Deque<PlanningContext.ReviewBlock> reviewQueue = new java.util.ArrayDeque<>(reviews);

        int dayIndex = ctx.frozenDays().size() + 1;

        for (LocalDate date : contentDays) {
            int capacity = ctx.capacityFor(date);
            int ceiling = (int) Math.floor(capacity * ctx.config().overfillTolerance());
            int used = 0;
            int order = 0;
            List<SchedulingResult.ScheduledItem> items = new ArrayList<>();

            while (!reviewQueue.isEmpty() && used + reviewQueue.peek().effortMinutes() <= ceiling) {
                PlanningContext.ReviewBlock review = reviewQueue.poll();
                items.add(new SchedulingResult.ScheduledItem(
                        SchedulingResult.ItemType.REVIEW_BLOCK, null, review.courseTopicId(),
                        order++, review.effortMinutes(), DepthFlag.FULL));
                used += review.effortMinutes();
            }

            while (!queue.isEmpty()) {
                PlanningContext.SchedulableUnit unit = queue.peek();
                int remaining = ceiling - used;
                if (remaining <= 0) {
                    break;
                }
                if (unit.effortMinutes() <= remaining) {
                    queue.poll();
                    items.add(new SchedulingResult.ScheduledItem(
                            SchedulingResult.ItemType.LEARNING_UNIT, unit.learningUnitId(), null,
                            order++, unit.effortMinutes(), unit.depthFlag()));
                    used += unit.effortMinutes();
                } else if (unit.effortMinutes() > capacity
                        && remaining >= ctx.config().minUnitSplitMinutes()) {
                    // A unit larger than a whole day is split; anything smaller
                    // moves to tomorrow rather than being fragmented pointlessly.
                    queue.poll();
                    items.add(new SchedulingResult.ScheduledItem(
                            SchedulingResult.ItemType.LEARNING_UNIT, unit.learningUnitId(), null,
                            order++, remaining, unit.depthFlag()));
                    queue.addFirst(new PlanningContext.SchedulableUnit(
                            unit.learningUnitId(), unit.orderIndex(),
                            unit.effortMinutes() - remaining, unit.relevance(),
                            unit.depthFlag(), unit.markedKnown()));
                    used += remaining;
                    break;
                } else {
                    break;
                }
            }

            out.add(new SchedulingResult.ScheduledDay(
                    date, dayIndex++, capacity, SchedulingResult.DayKind.CONTENT, List.copyOf(items)));
        }

        // Anything the ladder kept but packing could not place goes onto the last
        // content day rather than vanishing. The invariant check downstream will
        // catch it if that ever exceeds tolerance.
        if (!queue.isEmpty() && !out.isEmpty()) {
            SchedulingResult.ScheduledDay last = out.get(out.size() - 1);
            List<SchedulingResult.ScheduledItem> items = new ArrayList<>(last.items());
            int order = items.size();
            for (PlanningContext.SchedulableUnit leftover : queue) {
                items.add(new SchedulingResult.ScheduledItem(
                        SchedulingResult.ItemType.LEARNING_UNIT, leftover.learningUnitId(), null,
                        order++, leftover.effortMinutes(), leftover.depthFlag()));
            }
            out.set(out.size() - 1, new SchedulingResult.ScheduledDay(
                    last.date(), last.dayIndex(), last.capacityMinutes(), last.kind(), List.copyOf(items)));
        }
    }

    private static void packReviewDays(PlanningContext ctx,
                                       List<LocalDate> reviewDays,
                                       List<SchedulingResult.ScheduledDay> out,
                                       int contentDayCount) {
        int dayIndex = ctx.frozenDays().size() + contentDayCount + 1;
        boolean examPlaced = false;

        for (LocalDate date : reviewDays) {
            int capacity = ctx.capacityFor(date);
            List<SchedulingResult.ScheduledItem> items = new ArrayList<>();

            // The final review exam goes on the first review day, leaving time
            // afterwards to act on what it reveals.
            if (!examPlaced) {
                items.add(new SchedulingResult.ScheduledItem(
                        SchedulingResult.ItemType.FINAL_REVIEW_EXAM, null, null, 0,
                        Math.min(capacity, 120), DepthFlag.FULL));
                examPlaced = true;
            }

            out.add(new SchedulingResult.ScheduledDay(
                    date, dayIndex++, capacity, SchedulingResult.DayKind.REVIEW, List.copyOf(items)));
        }
    }

    // ------------------------------------------------------------ infeasible

    private static SchedulingResult.Infeasible infeasible(PlanningContext ctx,
                                                          int availableMinutes,
                                                          int requiredMinutes) {
        int deficit = Math.max(0, requiredMinutes - availableMinutes);
        int studyDays = Math.max(1, (int) ctx.today().datesUntil(ctx.examDate()).count());
        int extraMinutesPerDay = (int) Math.ceil((double) deficit / studyDays);

        return new SchedulingResult.Infeasible(
                deficit, availableMinutes, requiredMinutes,
                List.of(
                        new SchedulingResult.Suggestion("INCREASE_DAILY_HOURS",
                                "add about " + extraMinutesPerDay + " minutes per day", extraMinutesPerDay),
                        new SchedulingResult.Suggestion("MOVE_EXAM_DATE",
                                "move the exam later by about "
                                        + (int) Math.ceil(deficit / Math.max(1.0, averageCapacity(ctx)))
                                        + " day(s)",
                                (int) Math.ceil(deficit / Math.max(1.0, averageCapacity(ctx)))),
                        new SchedulingResult.Suggestion("PRIORITY_MODE",
                                "study critical and high-relevance content only", 0)));
    }

    private static double averageCapacity(PlanningContext ctx) {
        return ctx.weeklyCapacityMinutes().values().stream()
                .mapToInt(Integer::intValue).average().orElse(1);
    }

    private record Compression(
            List<PlanningContext.SchedulableUnit> kept,
            List<SchedulingResult.DroppedUnit> dropped,
            List<SchedulingResult.Adjustment> adjustments,
            String mode,
            int requiredMinutes,
            int capacityMinutes) {

        boolean stillOverCapacity() {
            return requiredMinutes > capacityMinutes;
        }
    }
}
