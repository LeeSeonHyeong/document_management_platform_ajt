package com.ajt.backend.domain.schedule.api;

import com.ajt.backend.domain.schedule.model.Schedule;
import java.time.Instant;
import java.util.List;

/**
 * SCH 일정 상세 응답입니다. (계약: scheduleId ~ status, sourceDocument)
 * 수동/개인 일정처럼 원본문서가 없으면 sourceDocument는 null입니다.
 */
public record ScheduleDetailResponse(
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
        SourceDocument sourceDocument
) {

    public record SourceDocument(
            String originalFileName,
            String sourceFileUrl
    ) {
    }

    public static ScheduleDetailResponse from(Schedule schedule) {
        SourceDocument sourceDocument = schedule.hasSourceDocument()
                ? new SourceDocument(
                        schedule.sourceOriginalFileName(),
                        "/api/v1/schedules/%d/source-file".formatted(schedule.id()))
                : null;

        return new ScheduleDetailResponse(
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
                sourceDocument
        );
    }
}
