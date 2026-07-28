package com.ajt.backend.global.ai.client;

import java.util.List;

public record WikiContextSelectionResponse(
        List<String> wikiIds,
        String reason
) {
}
