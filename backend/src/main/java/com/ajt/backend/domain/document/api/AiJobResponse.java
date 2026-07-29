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

    public record DocumentResultResponse(
            String documentId,
            int order,
            String status,
            String currentStage,
            String summary,
            String failureReason
    ) {
    }
}
