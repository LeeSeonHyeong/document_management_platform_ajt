package com.ajt.backend.domain.wiki.api;

import java.util.List;

public record WikiChatMessageListResponse(List<WikiChatMessageResponse> items) {

    public WikiChatMessageListResponse {
        items = List.copyOf(items);
    }
}
