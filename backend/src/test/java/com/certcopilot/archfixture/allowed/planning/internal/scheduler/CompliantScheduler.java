package com.certcopilot.archfixture.allowed.planning.internal.scheduler;

import java.time.LocalDate;
import java.util.List;

/**
 * Correction P3 fixture: a scheduler that respects rule R1.
 *
 * <p>Pure, deterministic, no AI dependency - the shape the real
 * {@code SchedulerEngine} must keep. Rule R1 must PASS against this class.
 */
public class CompliantScheduler {

    public List<LocalDate> plan(LocalDate from, int days) {
        return java.util.stream.IntStream.range(0, days)
                .mapToObj(from::plusDays)
                .toList();
    }
}
