package com.certcopilot.domain.planning.internal.scheduler;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.certcopilot.shared.DepthFlag;

/**
 * Outcome of a scheduling run.
 *
 * <p>{@code Infeasible} is a value, not an exception, because "there is not
 * enough time left" is a legitimate answer the learner needs to act on. Nothing
 * is persisted in that case: an invalid schedule is never written, and the user
 * is offered real options instead of a plan that quietly cannot be finished.
 */
public sealed interface SchedulingResult {

    record Scheduled(
            List<ScheduledDay> days,
            List<DroppedUnit> droppedUnits,
            List<Adjustment> adjustments,
            String compressionMode,
            double effortRatio) implements SchedulingResult {
    }

    record Infeasible(
            int deficitMinutes,
            int availableMinutes,
            int requiredMinutes,
            List<Suggestion> suggestions) implements SchedulingResult {
    }

    record ScheduledDay(
            LocalDate date,
            int dayIndex,
            int capacityMinutes,
            DayKind kind,
            List<ScheduledItem> items) {

        public int allocatedMinutes() {
            return items.stream().mapToInt(ScheduledItem::allottedMinutes).sum();
        }
    }

    enum DayKind { CONTENT, REVIEW }

    enum ItemType { LEARNING_UNIT, REVIEW_BLOCK, FINAL_REVIEW_EXAM }

    record ScheduledItem(
            ItemType type,
            UUID learningUnitId,
            UUID courseTopicId,
            int orderIndex,
            int allottedMinutes,
            DepthFlag depthFlag) {
    }

    /** A unit the compression ladder removed, always with a reason the UI can show. */
    record DroppedUnit(UUID learningUnitId, String reasonCode) {
    }

    record Adjustment(String reasonCode, String detail) {
    }

    /** Concrete options offered when no valid schedule exists. */
    record Suggestion(String code, String detail, int parameter) {
    }
}
