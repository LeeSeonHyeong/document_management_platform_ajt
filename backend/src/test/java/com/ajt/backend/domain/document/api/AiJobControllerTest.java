package com.ajt.backend.domain.document.api;

import static org.hamcrest.Matchers.empty;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.document.service.AiJobQueryService;
import com.ajt.backend.domain.document.service.AiJobCancelService;
import com.ajt.backend.domain.document.service.AiJobStartService;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import com.ajt.backend.global.error.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("AI 작업 상태 조회 API")
class AiJobControllerTest {

    private final AiJobQueryService aiJobQueryService = org.mockito.Mockito.mock(AiJobQueryService.class);
    private final AiJobCancelService aiJobCancelService = org.mockito.Mockito.mock(AiJobCancelService.class);
    private final AiJobStartService aiJobStartService = org.mockito.Mockito.mock(AiJobStartService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AiJobController(aiJobQueryService, aiJobCancelService, aiJobStartService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("AI 작업 상태 조회 성공 시 job과 문서별 처리 결과를 반환한다")
    void getsAiJob() throws Exception {
        given(aiJobQueryService.getAiJob(42L)).willReturn(new AiJobResponse(
                "42",
                "processing",
                List.of(
                        new AiJobResponse.DocumentResultResponse(
                                "15",
                                1,
                                "processing",
                                "wiki_pending",
                                null,
                                null
                        ),
                        new AiJobResponse.DocumentResultResponse(
                                "16",
                                2,
                                "failed",
                                "parsing",
                                null,
                                "FastAPI timeout"
                        )
                ),
                LocalDateTime.parse("2026-07-28T15:00:00"),
                LocalDateTime.parse("2026-07-28T15:00:02"),
                null,
                null
        ));

        mockMvc.perform(get("/api/v1/ai-jobs/{jobId}", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("42"))
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.documentResults[0].documentId").value("15"))
                .andExpect(jsonPath("$.documentResults[0].order").value(1))
                .andExpect(jsonPath("$.documentResults[0].status").value("processing"))
                .andExpect(jsonPath("$.documentResults[0].currentStage").value("wiki_pending"))
                .andExpect(jsonPath("$.documentResults[0].summary").doesNotExist())
                .andExpect(jsonPath("$.documentResults[0].failureReason").doesNotExist())
                .andExpect(jsonPath("$.documentResults[1].documentId").value("16"))
                .andExpect(jsonPath("$.documentResults[1].status").value("failed"))
                .andExpect(jsonPath("$.documentResults[1].currentStage").value("parsing"))
                .andExpect(jsonPath("$.documentResults[1].failureReason").value("FastAPI timeout"))
                .andExpect(jsonPath("$.createdAt").value("2026-07-28T15:00:00"))
                .andExpect(jsonPath("$.startedAt").value("2026-07-28T15:00:02"))
                .andExpect(jsonPath("$.finishedAt").doesNotExist())
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    @DisplayName("존재하지 않는 AI 작업은 404 오류를 반환한다")
    void returnsNotFoundForUnknownJob() throws Exception {
        given(aiJobQueryService.getAiJob(42L)).willThrow(new BusinessException(ErrorCode.AI_JOB_NOT_FOUND));

        mockMvc.perform(get("/api/v1/ai-jobs/{jobId}", 42L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AI_JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("AI 작업을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.path").value("/api/v1/ai-jobs/42"))
                .andExpect(jsonPath("$.fieldErrors", empty()));
    }

    @Test
    @DisplayName("대기 작업 시작은 202와 processing 상태를 반환한다")
    void acceptsAiJobStart() throws Exception {
        given(aiJobStartService.start(42L)).willReturn(new AiJobStartResponse("42", "processing"));

        mockMvc.perform(post("/api/v1/ai-jobs/{jobId}/start", 42L))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("42"))
                .andExpect(jsonPath("$.status").value("processing"));
    }

    @Test
    @DisplayName("이미 시작된 작업을 다시 시작하면 409 오류를 반환한다")
    void returnsConflictForAlreadyStartedJob() throws Exception {
        given(aiJobStartService.start(42L)).willThrow(new BusinessException(ErrorCode.RESOURCE_CONFLICT));

        mockMvc.perform(post("/api/v1/ai-jobs/{jobId}/start", 42L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    void acceptsAiJobCancellation() throws Exception {
        given(aiJobCancelService.cancel(42L)).willReturn(new AiJobCancelResponse("42", "cancelled"));

        mockMvc.perform(post("/api/v1/ai-jobs/{jobId}/cancel", 42L))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("42"))
                .andExpect(jsonPath("$.status").value("cancelled"));
    }
}
