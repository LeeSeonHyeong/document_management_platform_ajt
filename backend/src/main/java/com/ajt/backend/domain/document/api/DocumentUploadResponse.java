package com.ajt.backend.domain.document.api;

import java.time.LocalDateTime;
import java.util.List;

public record DocumentUploadResponse(
        String jobId,
        List<String> documentIds,
        String scopeKey,
        String status,
        LocalDateTime createdAt
) {
}
