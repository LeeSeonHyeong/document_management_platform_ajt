package com.ajt.backend.domain.schedule.api;

import com.ajt.backend.domain.schedule.model.Schedule;
import java.time.Instant;
import java.util.List;

/**
 * SCH 일정 생성 응답입니다. (계약: scheduleId ~ status, ownerId)
 * DB 숫자 ID는 문자열로 내려줍니다.
 */
public record ScheduleCreateResponse(
        String scheduleId,
        String title,
        String content,
        String targetText,
        String location,
        String visibilityType,
        List<String> departmentIds,
        Instant startAt,
        Instant endAt,
        String status,
        String ownerId
) {

    public static ScheduleCreateResponse from(Schedule schedule) {
        return new ScheduleCreateResponse(
                String.valueOf(schedule.id()),
                schedule.title(),
                schedule.content(),
                schedule.targetText(),
                schedule.location(),
                schedule.visibilityType().apiValue(),
                schedule.departmentIds().stream().map(String::valueOf).toList(),
                schedule.startAt(),
                schedule.endAt(),
                schedule.status().apiValue(),
                String.valueOf(schedule.authorId())
        );
    }
}
