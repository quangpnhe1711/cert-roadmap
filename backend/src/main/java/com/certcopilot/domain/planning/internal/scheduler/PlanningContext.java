package com.certcopilot.domain.planning.internal.scheduler;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.certcopilot.shared.DepthFlag;
import com.certcopilot.shared.ExamRelevance;

/**
 * Every input the scheduler is allowed to see.
 *
 * <p>Immutable and complete on purpose. {@code today} is passed in rather than
 * read from a clock so the engine is a pure function: the same context always
 * produces the same schedule, which is what makes property testing possible and
 * what makes a replan reproducible when investigating a complaint.
 *
 * @param frozenDays days already completed; the scheduler may read them but must
 *                   never emit a change to them
 */
public record PlanningContext(
        LocalDate today,
        LocalDate examDate,
        Map<DayOfWeek, Integer> weeklyCapacityMinutes,
        Set<LocalDate> blockedDates,
        List<FrozenDay> frozenDays,
        List<SchedulableUnit> pendingUnits,
        List<ReviewBlock> reviewBlocks,
        SchedulerConfig config) {

    public PlanningContext {
        weeklyCapacityMinutes = Map.copyOf(weeklyCapacityMinutes);
        blockedDates = Set.copyOf(blockedDates);
        frozenDays = List.copyOf(frozenDays);
        pendingUnits = List.copyOf(pendingUnits);
        reviewBlocks = List.copyOf(reviewBlocks);
    }

    public int capacityFor(LocalDate date) {
        if (blockedDates.contains(date)) {
            return 0;
        }
        return weeklyCapacityMinutes.getOrDefault(date.getDayOfWeek(), 0);
    }

    /** A unit waiting to be placed. Effort is already resolved at plan level. */
    public record SchedulableUnit(
            UUID learningUnitId,
            int orderIndex,
            int effortMinutes,
            ExamRelevance relevance,
            DepthFlag depthFlag,
            boolean markedKnown) {
    }

    /**
     * A weak-topic review inserted by the deterministic detector. Review always
     * goes first in its day: revisiting a shaky topic before new content is the
     * whole point of detecting it.
     */
    public record ReviewBlock(UUID courseTopicId, int effortMinutes, double weaknessScore) {
    }

    /** A completed day the scheduler must leave untouched. */
    public record FrozenDay(LocalDate date, int usedMinutes) {
    }

    public record SchedulerConfig(
            double reviewWindowRatio,
            int minReviewWindowDays,
            int maxReviewWindowDays,
            double overfillTolerance,
            int minUnitSplitMinutes) {

        public static SchedulerConfig defaults() {
            return new SchedulerConfig(0.15, 2, 7, 1.15, 20);
        }
    }
}
