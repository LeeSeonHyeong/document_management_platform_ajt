package com.ajt.backend.global.ai.client;

import java.util.Objects;

public record WikiContextSelectionRequest(
        String jobId,
        String documentId,
        String scopeKey,
        WikiDocumentChangeType changeType,
        String parsedMarkdown,
        String removedParsedMarkdown,
        String currentIndex
) {
    public WikiContextSelectionRequest {
        jobId = requireNotBlank(jobId, "jobId");
        documentId = requireNotBlank(documentId, "documentId");
        scopeKey = requireNotBlank(scopeKey, "scopeKey");
        changeType = Objects.requireNonNull(changeType, "changeType must not be null");
        currentIndex = requireNotBlank(currentIndex, "currentIndex");

        if (changeType != WikiDocumentChangeType.DOCUMENT_REMOVED) {
            parsedMarkdown = requireNotBlank(parsedMarkdown, "parsedMarkdown");
        }
        if (changeType != WikiDocumentChangeType.DOCUMENT_ADDED) {
            removedParsedMarkdown = requireNotBlank(removedParsedMarkdown, "removedParsedMarkdown");
        }
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
