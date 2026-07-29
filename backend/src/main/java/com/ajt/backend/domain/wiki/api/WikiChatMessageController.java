package com.ajt.backend.domain.wiki.api;

import com.ajt.backend.domain.wiki.service.WikiChatMessageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WikiChatMessageController {

    private final WikiChatMessageService wikiChatMessageService;

    public WikiChatMessageController(WikiChatMessageService wikiChatMessageService) {
        this.wikiChatMessageService = wikiChatMessageService;
    }

    @GetMapping("/api/v1/wikis/{wikiId}/chat-messages")
    public WikiChatMessageListResponse getChatMessages(@PathVariable long wikiId) {
        return wikiChatMessageService.getChatMessages(wikiId);
    }

    @PostMapping("/api/v1/wikis/{wikiId}/chat-messages")
    public WikiChatReplyResponse sendChatMessage(
            @PathVariable long wikiId,
            @RequestBody WikiChatMessageCreateRequest request
    ) {
        return wikiChatMessageService.sendChatMessage(wikiId, request.content());
    }
}
