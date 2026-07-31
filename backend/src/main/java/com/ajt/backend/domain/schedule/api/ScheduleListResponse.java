package com.ajt.backend.domain.schedule.api;

import com.ajt.backend.domain.schedule.model.Schedule;
import java.time.Instant;
import java.util.List;

/**
 * SCH 일정 목록 응답입니다. (계약: items 배열, 페이지네이션 없음)
 */
public record ScheduleListResponse(List<Item> items) {

    // TODO(계약/페이로드 검토): 계약 예시에 따라 목록 아이템에 content(TEXT)를 포함한다.
    //  달력 목록에서 항목마다 본문 전체를 내리면 페이로드가 커질 수 있어, 요약 필드만 내릴지 계약과 함께 재검토 필요.
    public record Item(
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
            // 수정(S15P11B106-87): 낙관적 동시성 토큰. 수정 폼이 목록 아이템으로 편집을 시작하므로, 목록에도
            //   담아 수정 요청의 expectedUpdatedAt으로 그대로 실어 보낼 수 있게 한다.
            Instant updatedAt
    ) {
    }

    public static ScheduleListResponse from(List<Schedule> schedules) {
        List<Item> items = schedules.stream()
                .map(ScheduleListResponse::toItem)
                .toList();
        return new ScheduleListResponse(items);
    }

    private static Item toItem(Schedule schedule) {
        return new Item(
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
                schedule.updatedAt()
        );
    }
}
