package com.certcopilot.domain.planning;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The capacity reality check: the first thing the product says that is both true
 * and useful, delivered before any file is uploaded or any model is called.
 *
 * <p>Pure arithmetic. It exists because the alternative - generating a beautiful
 * schedule that cannot be finished - is the failure mode that makes study
 * planners untrustworthy.
 */
public final class CapacityCalculator {

    private CapacityCalculator() {
    }

    /**
     * @param typicalEffortLow  lower bound of typical preparation effort for the
     *                          certification, in minutes
     * @param typicalEffortHigh upper bound, in minutes
     */
    public static Result calculate(LocalDate today,
                                   LocalDate examDate,
                                   Map<DayOfWeek, Integer> weeklyCapacityMinutes,
                                   Set<LocalDate> blockedDates,
                                   int typicalEffortLow,
                                   int typicalEffortHigh) {

        if (!examDate.isAfter(today)) {
            return new Result(0, 0, 0, 0, 0, Verdict.EXAM_DATE_PASSED,
                    List.of("Ngày thi phải nằm sau hôm nay."));
        }

        List<LocalDate> studyDays = today.datesUntil(examDate)
                .filter(d -> !blockedDates.contains(d))
                .filter(d -> weeklyCapacityMinutes.getOrDefault(d.getDayOfWeek(), 0) > 0)
                .toList();

        int totalMinutes = studyDays.stream()
                .mapToInt(d -> weeklyCapacityMinutes.getOrDefault(d.getDayOfWeek(), 0))
                .sum();

        long calendarDays = today.datesUntil(examDate).count();
        int blockedCount = (int) today.datesUntil(examDate).filter(blockedDates::contains).count();

        Verdict verdict;
        if (totalMinutes == 0) {
            verdict = Verdict.NO_CAPACITY;
        } else if (totalMinutes >= typicalEffortHigh) {
            verdict = Verdict.COMFORTABLE;
        } else if (totalMinutes >= typicalEffortLow) {
            verdict = Verdict.TIGHT;
        } else {
            verdict = Verdict.INSUFFICIENT;
        }

        return new Result(
                (int) calendarDays,
                studyDays.size(),
                blockedCount,
                totalMinutes,
                studyDays.isEmpty() ? 0 : totalMinutes / studyDays.size(),
                verdict,
                advice(verdict, totalMinutes, typicalEffortLow, typicalEffortHigh, studyDays.size()));
    }

    private static List<String> advice(Verdict verdict, int totalMinutes,
                                       int low, int high, int studyDays) {
        return switch (verdict) {
            case COMFORTABLE -> List.of(
                    "Bạn có đủ thời gian, kể cả khi nghỉ vài ngày.");
            case TIGHT -> List.of(
                    "Sát nút nhưng khả thi. Cố gắng không bỏ ngày nào.",
                    "Tải tài liệu lên để hệ thống tính chính xác theo khối lượng thật.");
            case INSUFFICIENT -> {
                int deficit = low - totalMinutes;
                int extraPerDay = studyDays == 0 ? 0 : (int) Math.ceil((double) deficit / studyDays);
                yield List.of(
                        "Thời gian hiện tại thấp hơn mức thường cần cho chứng chỉ này.",
                        "Tăng thêm khoảng " + extraPerDay + " phút mỗi ngày,",
                        "hoặc dời ngày thi, hoặc học theo chế độ ưu tiên.");
            }
            case NO_CAPACITY -> List.of(
                    "Bạn chưa khai báo giờ học nào. Hãy chọn ít nhất một ngày trong tuần.");
            case EXAM_DATE_PASSED -> List.of("Ngày thi phải nằm sau hôm nay.");
        };
    }

    public enum Verdict { COMFORTABLE, TIGHT, INSUFFICIENT, NO_CAPACITY, EXAM_DATE_PASSED }

    public record Result(
            int calendarDays,
            int studyDays,
            int blockedDays,
            int totalCapacityMinutes,
            int averageMinutesPerStudyDay,
            Verdict verdict,
            List<String> advice) {
    }
}
