package com.ajt.backend.domain.schedule.api.internal;

import com.ajt.backend.domain.schedule.service.InternalScheduleQueryService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FastAPI 전용 일정 조회 창구입니다. 챗봇 에이전트가 일정 질문에 답할 때 직접 호출합니다.
 *
 * <p>내부 API 키 검사는 {@code /internal/**} 를 거르는 기존 필터가 담당한다
 * ({@code InternalApiKeyFilter}). 그 키는 "AI 서버다"만 증명하므로 <b>누구 대신 묻는지는
 * {@code questionId} 로 판정한다</b> — 판정은 서비스가 한다.
 *
 * <p>Wiki 조회 창구와 달리 열람 허가값(capability)을 쓰지 않는다. 일정은 이번에 새로 만드는
 * 창구이므로 재시작·다중 서버·큐 대기에 강한 질문 번호 방식을 쓴다(설계 §3.1·§7).
 */
@RestController
public class InternalScheduleQueryController {

    private final InternalScheduleQueryService queryService;

    public InternalScheduleQueryController(InternalScheduleQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/internal/v1/schedules")
    public InternalScheduleQueryService.ScheduleListResult list(
            @RequestParam long questionId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return queryService.list(questionId, from, to, keyword, limit);
    }

    @GetMapping("/internal/v1/schedules/{scheduleId}")
    public InternalScheduleQueryService.ScheduleDetail detail(
            @PathVariable long scheduleId,
            @RequestParam long questionId
    ) {
        return queryService.detail(questionId, scheduleId);
    }
}
