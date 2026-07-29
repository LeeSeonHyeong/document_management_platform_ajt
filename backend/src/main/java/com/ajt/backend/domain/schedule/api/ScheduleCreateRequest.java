package com.ajt.backend.domain.schedule.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * SCH 수동/개인 일정 생성 요청입니다.
 * visibilityType은 all/department/personal, departmentIds는 department 공개일 때만 사용합니다.
 */
public record ScheduleCreateRequest(
        @NotBlank(message = "제목을 입력해주세요.")
        @Size(max = 200, message = "제목은 200자 이하로 입력해주세요.")
        String title,

        String content,

        @Size(max = 500, message = "대상 설명은 500자 이하로 입력해주세요.")
        String targetText,

        @Size(max = 200, message = "장소는 200자 이하로 입력해주세요.")
        String location,

        @NotBlank(message = "공개 범위를 입력해주세요.")
        String visibilityType,

        List<String> departmentIds,

        @NotNull(message = "시작 시각을 입력해주세요.")
        Instant startAt,

        @NotNull(message = "종료 시각을 입력해주세요.")
        Instant endAt
) {
}
