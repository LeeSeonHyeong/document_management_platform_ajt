package com.ajt.backend.global.ai.client;

public interface AiClient {

    SourceParseResponse parseSource(SourceParseRequest request);

    ScheduleExtractionResponse extractSchedules(ScheduleExtractionRequest request);

    WikiContextSelectionResponse selectWikiContext(WikiContextSelectionRequest request);

    WikiTransformationResponse transformWiki(WikiTransformationRequest request);

    WikiEditResponse editWiki(WikiEditRequest request);
}
