package com.ajt.backend.domain.document.api;

import java.time.LocalDateTime;
import java.util.List;

public record AiJobResponse(
        String jobId,
        String status,
        List<DocumentResultResponse> documentResults,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String failureReason
) {

    /**
     * @param currentStage 지금 어디까지 왔는지. 문서 상태에서 역산하므로 <b>실패 지점이
     *                     아니다</b> — 실패한 문서는 어디서 실패했든 {@code parsing}이 된다.
     * @param failureStage 실제로 어디서 실패했는지. AI 오류 응답이 실어 준 값을
     *                     {@code ai_job.document_results}에 기록해 둔 것이며,
     *                     실패하지 않았거나 단계를 알 수 없으면 {@code null}이다.
     */
    public record DocumentResultResponse(
            String documentId,
            int order,
            String status,
            String currentStage,
            String summary,
            String failureReason,
            String failureStage
    ) {
    }
}
