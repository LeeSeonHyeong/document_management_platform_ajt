package com.ajt.backend.global.ai.client;

import java.util.List;
import java.util.Objects;

public record ScheduleExtractionRequest(
        String sourceGroupKey,
        String parsedMarkdown,
        String visibilityType,
        List<String> departmentIds
) {
    public ScheduleExtractionRequest {
        sourceGroupKey = requireNotBlank(sourceGroupKey, "sourceGroupKey");
        parsedMarkdown = requireNotBlank(parsedMarkdown, "parsedMarkdown");
        visibilityType = requireNotBlank(visibilityType, "visibilityType");
        departmentIds = List.copyOf(Objects.requireNonNull(departmentIds, "departmentIds must not be null"));
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
