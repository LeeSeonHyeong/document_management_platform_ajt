package com.ajt.backend.global.ai.client;

public interface AiClient {

    SourceParseResponse parseSource(SourceParseRequest request);

    WikiContextSelectionResponse selectWikiContext(WikiContextSelectionRequest request);
}
