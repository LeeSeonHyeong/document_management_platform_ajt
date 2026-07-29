package com.ajt.backend.domain.wiki.api;

/**
 * 관리자 수정 지시 요청입니다. 계약 필드는 {@code content} 하나입니다.
 * 비어 있는지는 계약 오류 코드를 맞추기 위해 서비스에서 검사합니다.
 */
public record WikiChatMessageCreateRequest(String content) {
}
