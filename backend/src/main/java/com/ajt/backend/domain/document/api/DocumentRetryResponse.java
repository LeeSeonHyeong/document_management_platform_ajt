package com.ajt.backend.domain.document.api;

import java.time.Instant;

public record DocumentRetryResponse(
        String jobId,
        String documentId,
        String status,
        Instant createdAt
) {
}
