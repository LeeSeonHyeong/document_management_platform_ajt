package com.ajt.backend.domain.wiki.api;

import com.ajt.backend.domain.wiki.model.WikiChatMessage;
import java.time.Instant;

/**
 * Wiki 관리자 대화 1건입니다. {@code senderType}은 계약대로 소문자 admin·agent입니다.
 */
public record WikiChatMessageResponse(
        String messageId,
        String senderType,
        String content,
        Instant createdAt
) {
    public static WikiChatMessageResponse from(WikiChatMessage message) {
        return new WikiChatMessageResponse(
                String.valueOf(message.id()),
                message.senderType().apiValue(),
                message.content(),
                message.createdAt()
        );
    }
}
