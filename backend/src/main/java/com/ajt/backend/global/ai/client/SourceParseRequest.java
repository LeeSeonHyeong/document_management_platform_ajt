package com.ajt.backend.global.ai.client;

import java.util.Objects;
import org.springframework.core.io.Resource;

public record SourceParseRequest(
        String requestId,
        SourceType sourceType,
        String sourceId,
        Resource file,
        String originalFileName,
        String mimeType
) {
    public SourceParseRequest {
        requestId = requireNotBlank(requestId, "requestId");
        sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        sourceId = requireNotBlank(sourceId, "sourceId");
        file = Objects.requireNonNull(file, "file must not be null");
        originalFileName = requireNotBlank(originalFileName, "originalFileName");
        mimeType = requireNotBlank(mimeType, "mimeType");
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
