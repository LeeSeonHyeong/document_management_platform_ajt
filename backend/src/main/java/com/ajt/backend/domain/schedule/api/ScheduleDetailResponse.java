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
        // TODO(39번 일정 원본문서 업로드/파싱 - 팀원 내부 작업 연동 시 재검토):
        //  schedule 테이블에 원본 파일명 컬럼이 없어 지금은 저장 경로의 basename으로 대체한다.
        //  파싱 연동이 붙으면 실제 원본 파일명 저장 방식을 팀원과 확정하고 이 로직을 교체할 것.
        //  현재 브랜치는 원본문서를 만드는 경로가 없어 수동·개인 일정은 sourceDocument=null 이다.
        SourceDocument sourceDocument = schedule.hasSourceDocument()
                ? new SourceDocument(
                        fileNameOf(schedule.sourceOriginalPath()),
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

    private static String fileNameOf(String path) {
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(separator + 1);
    }
}
