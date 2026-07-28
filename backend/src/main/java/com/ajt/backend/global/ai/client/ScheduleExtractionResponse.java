package com.ajt.backend.global.ai.client;

import java.time.Instant;
import java.util.List;

public record ScheduleExtractionResponse(
        String status,
        List<ExtractedSchedule> schedules,
        List<String> warnings
) {

    /**
     * 추출된 일정 하나입니다.
     * 시각은 계약대로 문자열로 받고, 파싱은 클라이언트 응답 검증에서 한 번 확인한다.
     */
    public record ExtractedSchedule(
            Integer order,
            String title,
            String content,
            String targetText,
            String location,
            String visibilityType,
            List<String> departmentIds,
            String startAt,
            String endAt
    ) {

        public Instant startAtInstant() {
            return Instant.parse(startAt);
        }

        public Instant endAtInstant() {
            return Instant.parse(endAt);
        }
    }
}
