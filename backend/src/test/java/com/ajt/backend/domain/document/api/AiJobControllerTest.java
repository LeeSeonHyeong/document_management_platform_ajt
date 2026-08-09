package com.ajt.backend.domain.document.api;

import static org.hamcrest.Matchers.empty;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.document.service.AiJobQueryService;
import com.ajt.backend.domain.document.service.AiJobCancelService;
import com.ajt.backend.domain.document.service.AiJobCreateService;
import com.ajt.backend.domain.document.service.AiJobStartService;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import com.ajt.backend.global.error.GlobalExceptionHandler;
import java.time.Instant;
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
    private final AiJobCreateService aiJobCreateService = org.mockito.Mockito.mock(AiJobCreateService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AiJobController(aiJobQueryService, aiJobCancelService, aiJobStartService, aiJobCreateService))
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
                                "문서-15.pdf",
                                "document_added",
                                1,
                                "processing",
                                "wiki_pending",
                                null,
                                null,
                                null,
                                List.of()
                        ),
                        new AiJobResponse.DocumentResultResponse(
                                "16",
                                "문서-16.pdf",
                                "document_added",
                                2,
                                "failed",
                                "parsing",
                                null,
                                "FastAPI timeout",
                                "agent_timeout",
                                List.of()
                        )
                ),
                Instant.parse("2026-07-28T15:00:00Z"),
                Instant.parse("2026-07-28T15:00:02Z"),
                null,
                null
        ));

        mockMvc.perform(get("/api/v1/ai-jobs/{jobId}", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("42"))
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.documentResults[0].documentId").value("15"))
                .andExpect(jsonPath("$.documentResults[0].changeType").value("document_added"))
                .andExpect(jsonPath("$.documentResults[0].order").value(1))
                .andExpect(jsonPath("$.documentResults[0].status").value("processing"))
                .andExpect(jsonPath("$.documentResults[0].currentStage").value("wiki_pending"))
                .andExpect(jsonPath("$.documentResults[0].summary").doesNotExist())
                .andExpect(jsonPath("$.documentResults[0].failureReason").doesNotExist())
                .andExpect(jsonPath("$.documentResults[0].affectedWikis", empty()))
                .andExpect(jsonPath("$.documentResults[1].documentId").value("16"))
                .andExpect(jsonPath("$.documentResults[1].status").value("failed"))
                .andExpect(jsonPath("$.documentResults[1].currentStage").value("parsing"))
                .andExpect(jsonPath("$.documentResults[1].failureReason").value("FastAPI timeout"))
                // currentStage 는 문서 상태에서 역산한 값이라 실패 지점이 아니다.
                // 어디서 실패했는지는 failureStage 만 안다.
                .andExpect(jsonPath("$.documentResults[1].failureStage").value("agent_timeout"))
                .andExpect(jsonPath("$.documentResults[1].affectedWikis", empty()))
                .andExpect(jsonPath("$.createdAt").value("2026-07-28T15:00:00Z"))
                .andExpect(jsonPath("$.startedAt").value("2026-07-28T15:00:02Z"))
                .andExpect(jsonPath("$.finishedAt").doesNotExist())
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    @DisplayName("작업 이력 목록은 items 배열과 페이지 정보를 반환한다")
    void listsAiJobs() throws Exception {
        given(aiJobQueryService.listAiJobs(null, null)).willReturn(new AiJobListResponse(
                List.of(new AiJobResponse(
                        "42",
                        "completed",
                        List.of(new AiJobResponse.DocumentResultResponse(
                                "15",
                                "문서-15.pdf",
                                "document_removed",
                                1,
                                "completed",
                                "wiki_applied",
                                "인사규정을 Wiki에 반영했습니다.",
                                null,
                                null,
                                List.of(new AiJobResponse.AffectedWikiResponse("101", "휴가 규정", true))
                        )),
                        Instant.parse("2026-07-26T15:24:00Z"),
                        Instant.parse("2026-07-26T15:24:01Z"),
                        Instant.parse("2026-07-26T15:26:15Z"),
                        null
                )),
                1,
                20,
                1,
                1
        ));

        mockMvc.perform(get("/api/v1/ai-jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].jobId").value("42"))
                .andExpect(jsonPath("$.items[0].status").value("completed"))
                .andExpect(jsonPath("$.items[0].documentResults[0].summary")
                        .value("인사규정을 Wiki에 반영했습니다."))
                .andExpect(jsonPath("$.items[0].documentResults[0].changeType").value("document_removed"))
                .andExpect(jsonPath("$.items[0].documentResults[0].affectedWikis[0].wikiId").value("101"))
                .andExpect(jsonPath("$.items[0].documentResults[0].affectedWikis[0].title").value("휴가 규정"))
                .andExpect(jsonPath("$.items[0].documentResults[0].affectedWikis[0].deleted").value(true))
                // 소요 시간은 프론트가 이 둘의 차로 계산한다.
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-07-26T15:24:01Z"))
                .andExpect(jsonPath("$.items[0].finishedAt").value("2026-07-26T15:26:15Z"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("작업이 없으면 빈 배열을 반환한다")
    void listsEmptyAiJobs() throws Exception {
        given(aiJobQueryService.listAiJobs(null, null))
                .willReturn(new AiJobListResponse(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get("/api/v1/ai-jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()));
    }

    @Test
    @DisplayName("page·size 는 서비스로 그대로 넘어간다")
    void passesPagingThrough() throws Exception {
        given(aiJobQueryService.listAiJobs(2, 5))
                .willReturn(new AiJobListResponse(List.of(), 2, 5, 0, 0));

        mockMvc.perform(get("/api/v1/ai-jobs").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5));
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
