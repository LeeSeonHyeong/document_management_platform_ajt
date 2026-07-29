package com.ajt.backend.domain.wiki.api;

/**
 * 관리자 수정 지시 전송 응답입니다.
 * 관리자 메시지, 에이전트 응답과 수정이 반영된 Wiki 상세를 함께 돌려줍니다.
 */
public record WikiChatReplyResponse(
        WikiChatMessageResponse adminMessage,
        WikiChatMessageResponse agentMessage,
        WikiDetailResponse updatedWiki
) {
}
