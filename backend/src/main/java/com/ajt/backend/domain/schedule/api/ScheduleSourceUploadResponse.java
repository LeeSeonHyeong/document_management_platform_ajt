package com.ajt.backend.domain.schedule.api;

import com.ajt.backend.domain.schedule.model.Schedule;
import java.time.Instant;
import java.util.List;

/**
 * SCH-SOURCE-UPLOAD 일정 원본문서 업로드 응답입니다.
 * 일정을 추출하면 status는 extracted, 한 건도 없으면 no_schedule입니다.
 */
public record ScheduleSourceUploadResponse(
        String sourceGroupKey,
        String status,
        List<DraftSchedule> draftSchedules
) {

    private static final String STATUS_EXTRACTED = "extracted";
    private static final String STATUS_NO_SCHEDULE = "no_schedule";

    public record DraftSchedule(
            String scheduleId,
            String title,
            String content,
            String targetText,
            String location,
            String visibilityType,
            List<String> departmentIds,
            Instant startAt,
            Instant endAt,
            String status
    ) {

        static DraftSchedule from(Schedule schedule) {
            return new DraftSchedule(
                    String.valueOf(schedule.id()),
                    schedule.title(),
                    schedule.content(),
                    schedule.targetText(),
                    schedule.location(),
                    schedule.visibilityType().apiValue(),
                    schedule.departmentIds().stream().map(String::valueOf).toList(),
                    schedule.startAt(),
                    schedule.endAt(),
                    schedule.status().apiValue()
            );
        }
    }

    public static ScheduleSourceUploadResponse extracted(String sourceGroupKey, List<Schedule> schedules) {
        return new ScheduleSourceUploadResponse(
                sourceGroupKey,
                STATUS_EXTRACTED,
                schedules.stream().map(DraftSchedule::from).toList()
        );
    }

    public static ScheduleSourceUploadResponse noSchedule(String sourceGroupKey) {
        return new ScheduleSourceUploadResponse(sourceGroupKey, STATUS_NO_SCHEDULE, List.of());
    }

    public boolean hasSchedules() {
        return !draftSchedules.isEmpty();
    }
}
