package com.ajt.backend.global.ai.client;

import java.util.List;

public record SourceParseResponse(
        String requestId,
        SourceType sourceType,
        String sourceId,
        String parsedMarkdown,
        List<String> warnings
) {
}
