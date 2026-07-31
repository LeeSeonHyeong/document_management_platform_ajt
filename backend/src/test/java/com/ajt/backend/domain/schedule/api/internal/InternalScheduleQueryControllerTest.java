package com.ajt.backend.domain.schedule.api.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.schedule.service.InternalScheduleQueryService;
import com.ajt.backend.domain.schedule.service.InternalScheduleQueryService.ScheduleDetail;
import com.ajt.backend.domain.schedule.service.InternalScheduleQueryService.ScheduleListItem;
import com.ajt.backend.domain.schedule.service.InternalScheduleQueryService.ScheduleListResult;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import com.ajt.backend.global.error.GlobalExceptionHandler;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("내부 일정 조회 창구 라우팅")
class InternalScheduleQueryControllerTest {

    private final InternalScheduleQueryService queryService = mock(InternalScheduleQueryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalScheduleQueryController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("일정 목록은 items 와 truncated 를 돌려준다")
    void listReturnsItemsAndTruncated() throws Exception {
        given(queryService.list(eq(500L), any(), any(), isNull(), anyInt()))
                .willReturn(new ScheduleListResult(List.of(
                        new ScheduleListItem("31", "8월 워크샵",
                                "2026-08-03T01:00:00Z", "2026-08-03T09:00:00Z",
                                "전사", "본사")), false));

        mockMvc.perform(get("/internal/v1/schedules")
                        .param("questionId", "500")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].scheduleId").value("31"))
                .andExpect(jsonPath("$.items[0].title").value("8월 워크샵"))
                .andExpect(jsonPath("$.items[0].startAt").value("2026-08-03T01:00:00Z"))
                .andExpect(jsonPath("$.items[0].targetText").value("전사"))
                .andExpect(jsonPath("$.items[0].location").value("본사"))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    @DisplayName("keyword 와 limit 을 서비스에 그대로 전달하고, 기본 limit 은 50 이다")
    void passesKeywordAndDefaultLimit() throws Exception {
        given(queryService.list(eq(500L), any(), any(), eq("워크샵"), eq(50)))
                .willReturn(new ScheduleListResult(List.of(), false));

        mockMvc.perform(get("/internal/v1/schedules")
                        .param("questionId", "500")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31")
                        .param("keyword", "워크샵"))
                .andExpect(status().isOk());

        then(queryService).should().list(500L,
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"), "워크샵", 50);
    }

    @Test
    @DisplayName("일정 상세는 content 를 포함한다")
    void detailIncludesContent() throws Exception {
        given(queryService.detail(500L, 31L)).willReturn(new ScheduleDetail(
                "31", "8월 워크샵", "전사 워크샵 안내",
                "2026-08-03T01:00:00Z", "2026-08-03T09:00:00Z", "전사", "본사"));

        mockMvc.perform(get("/internal/v1/schedules/31")
                        .param("questionId", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value("31"))
                .andExpect(jsonPath("$.content").value("전사 워크샵 안내"));
    }

    @Test
    @DisplayName("questionId 가 없으면 400 이다 — 권한을 판정할 근거가 없다")
    void withoutQuestionIdItIs400() throws Exception {
        mockMvc.perform(get("/internal/v1/schedules")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isBadRequest());

        then(queryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("from·to 가 없으면 400 이다")
    void withoutPeriodItIs400() throws Exception {
        mockMvc.perform(get("/internal/v1/schedules")
                        .param("questionId", "500"))
                .andExpect(status().isBadRequest());

        then(queryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("날짜 형식이 잘못되면 400 이다")
    void malformedDateIsRejected() throws Exception {
        mockMvc.perform(get("/internal/v1/schedules")
                        .param("questionId", "500")
                        .param("from", "2026-08-32")
                        .param("to", "2026-08-31"))
                .andExpect(status().isBadRequest());

        then(queryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("없는 질문 번호는 계약 오류 코드 QUESTION_NOT_FOUND 로 나간다")
    void unknownQuestionUsesContractErrorCode() throws Exception {
        given(queryService.list(eq(999L), any(), any(), isNull(), anyInt()))
                .willThrow(new BusinessException(ErrorCode.QUESTION_NOT_FOUND));

        mockMvc.perform(get("/internal/v1/schedules")
                        .param("questionId", "999")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUESTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("권한 밖 일정 상세는 SCHEDULE_NOT_FOUND 로 존재를 숨긴다")
    void invisibleScheduleIsHidden() throws Exception {
        given(queryService.detail(500L, 32L))
                .willThrow(new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));

        mockMvc.perform(get("/internal/v1/schedules/32")
                        .param("questionId", "500"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
    }
}
