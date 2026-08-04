package com.ajt.backend.domain.wiki.api;

import com.ajt.backend.domain.wiki.model.WikiChatMessage;
import java.time.Instant;

/**
 * Wiki 관리자 대화 1건입니다. {@code senderType}은 계약대로 소문자 admin·agent입니다.
 *
 * <p>{@code wikiId}·{@code wikiTitle}은 이 메시지가 어느 위키에 관한 것인지 보여준다(S15P11B106-243).
 * 관리자 메시지는 보낸 시점에 보고 있던 위키, 에이전트 메시지는 그 지시로 실제 바뀐 위키다
 * (여러 위키가 바뀌었으면 첫 번째만). {@code wiki_id}가 하드 삭제로 NULL이면 {@code wikiId}도
 * null이며, 화면은 그럴 때 링크 없이 제목 텍스트만 보여준다.
 */
public record WikiChatMessageResponse(
        String messageId,
        String senderType,
        String content,
        Instant createdAt,
        String wikiId,
        String wikiTitle
) {
    public static WikiChatMessageResponse from(WikiChatMessage message) {
        return new WikiChatMessageResponse(
                String.valueOf(message.id()),
                message.senderType().apiValue(),
                message.content(),
                message.createdAt(),
                message.wikiId() == null ? null : String.valueOf(message.wikiId()),
                message.wikiTitleSnapshot()
        );
    }
}
