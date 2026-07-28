package com.ajt.backend.domain.document.api;

import java.time.LocalDateTime;

public record DocumentRetryResponse(
        String jobId,
        String documentId,
        String status,
        LocalDateTime createdAt
) {
}
