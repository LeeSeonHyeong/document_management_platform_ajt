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
        // 수정(S15P11B106-87): 낙관적 동시성 토큰. 수정 화면은 이 값을 그대로 수정 요청의 expectedUpdatedAt으로 보낸다.
        //   수정 성공 응답에는 증가된 최신 값이 담긴다.
        Instant updatedAt,
        SourceDocument sourceDocument
) {

    public record SourceDocument(
            String originalFileName,
            String sourceFileUrl
    ) {
    }

    // 수정: from(Schedule) → from(Schedule, boolean)로 변경. 원본문서 노출 여부를 인자로 받는다.
    //       사원에게는 일정 원본문서의 파일명·다운로드 URL을 노출하면 안 되므로(FR-SCH-009),
    //       관리자(includeSourceDocument=true)일 때만 sourceDocument를 채우고, 사원이면 null로 둔다.
    public static ScheduleDetailResponse from(Schedule schedule, boolean includeSourceDocument) {
        // 수정: 기존엔 hasSourceDocument()만 보고 채웠으나, 관리자 여부(includeSourceDocument)도 함께 확인한다.
        SourceDocument sourceDocument = (includeSourceDocument && schedule.hasSourceDocument())
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
                schedule.updatedAt(),
                sourceDocument
        );
    }
}
