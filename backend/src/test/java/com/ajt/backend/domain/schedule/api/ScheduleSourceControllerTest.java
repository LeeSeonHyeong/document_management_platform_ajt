package com.ajt.backend.domain.schedule.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.schedule.service.ScheduleSourceService;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import com.ajt.backend.global.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

@DisplayName("일정 원본문서 업로드 API")
class ScheduleSourceControllerTest {

    private final ScheduleSourceService scheduleSourceService = mock(ScheduleSourceService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ScheduleSourceController(scheduleSourceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MockMultipartFile xlsx() {
        return new MockMultipartFile(
                "file",
                "8월일정.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "일정".getBytes());
    }

    @Test
    @DisplayName("일정을 추출하면 201과 sourceGroupKey·draftSchedules를 반환한다")
    void returnsCreatedWhenSchedulesExtracted() throws Exception {
        given(scheduleSourceService.upload(any(), any(MultipartFile.class), any(), anyList()))
                .willReturn(new ScheduleSourceUploadResponse(
                        "schedule-source-20260728-ab12cd34",
                        "extracted",
                        List.of(new ScheduleSourceUploadResponse.DraftSchedule(
                                "31",
                                "8월 휴가 일정",
                                "개발부 휴가 일정",
                                "개발부",
                                "본사",
                                "department",
                                List.of("1", "2"),
                                Instant.parse("2026-08-03T01:00:00Z"),
                                Instant.parse("2026-08-03T03:00:00Z"),
                                "draft"
                        ))
                ));

        mockMvc.perform(multipart("/api/v1/schedule-sources")
                        .file(xlsx())
                        .param("visibilityType", "department")
                        .param("departmentIds", "1", "2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceGroupKey").value("schedule-source-20260728-ab12cd34"))
                .andExpect(jsonPath("$.status").value("extracted"))
                .andExpect(jsonPath("$.draftSchedules[0].scheduleId").value("31"))
                .andExpect(jsonPath("$.draftSchedules[0].status").value("draft"))
                .andExpect(jsonPath("$.draftSchedules[0].departmentIds[0]").value("1"));
    }

    @Test
    @DisplayName("추출된 일정이 없으면 200과 no_schedule을 반환한다")
    void returnsOkWhenNoScheduleExtracted() throws Exception {
        given(scheduleSourceService.upload(any(), any(MultipartFile.class), any(), any()))
                .willReturn(ScheduleSourceUploadResponse.noSchedule("schedule-source-20260728-ab12cd34"));

        mockMvc.perform(multipart("/api/v1/schedule-sources")
                        .file(xlsx())
                        .param("visibilityType", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("no_schedule"))
                .andExpect(jsonPath("$.draftSchedules").isEmpty());
    }

    @Test
    @DisplayName("형식 오류는 400 INVALID_SCHEDULE_SOURCE로 반환한다")
    void returnsBadRequestOnInvalidSource() throws Exception {
        given(scheduleSourceService.upload(any(), any(MultipartFile.class), any(), any()))
                .willThrow(new BusinessException(ErrorCode.INVALID_SCHEDULE_SOURCE));

        mockMvc.perform(multipart("/api/v1/schedule-sources")
                        .file(xlsx())
                        .param("visibilityType", "all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE_SOURCE"));
    }

    @Test
    @DisplayName("관리자가 아니면 403으로 거절한다")
    void returnsForbiddenForNonAdmin() throws Exception {
        given(scheduleSourceService.upload(any(), any(MultipartFile.class), any(), any()))
                .willThrow(new BusinessException(ErrorCode.ADMIN_PERMISSION_REQUIRED));

        mockMvc.perform(multipart("/api/v1/schedule-sources")
                        .file(xlsx())
                        .param("visibilityType", "all"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_REQUIRED"));
    }
}
