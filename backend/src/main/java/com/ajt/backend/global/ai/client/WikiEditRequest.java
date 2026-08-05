package com.ajt.backend.global.ai.client;

import java.util.List;

/**
 * `POST /internal/v1/wiki-edits` 요청입니다.
 * FastAPI가 정의되지 않은 필드를 거부하므로(extra=forbid) 계약에 있는 필드만 보낸다.
 *
 * <p>수정(S15P11B106-176): <b>본문을 밀어 보내지 않는다.</b> 수정 대상 Wiki 본문과 근거 원본문서를
 * 싣던 것을 걷어냈다 — 에이전트가 Wiki 조회 API로 직접 읽는다. 그래서 요청이 대상 ID와 지시,
 * 대화 이력, <b>조회 권한</b>(허가값·범위 버전), 관리자 지시 원본문서 ID로 끝난다.
 */
public record WikiEditRequest(
        String wikiId,
        String scopeKey,
        String instruction,
        List<ChatMessage> chatHistory,
        /*
         * 수정(S15P11B106-176): 선택 → 필수. 빠지면 에이전트가 조회 API를 부를 수 없어 수정 대상
         * 본문을 전혀 못 보고, 그 상태로 라이브를 덮을 수 있다. 로그·오류 응답에 남기지 않는다.
         */
        String wikiCapability,
        /** 요청 시작 시점의 범위 버전. 작업 중 같은 범위가 바뀌면 AI가 이 값으로 알아채고 중단한다. */
        Long scopeVersion,
        String adminInstructionDocumentId
) {
    public WikiEditRequest {
        wikiId = requireNotBlank(wikiId, "wikiId");
        scopeKey = requireNotBlank(scopeKey, "scopeKey");
        instruction = requireNotBlank(instruction, "instruction");
        chatHistory = chatHistory == null ? List.of() : List.copyOf(chatHistory);
        wikiCapability = requireNotBlank(wikiCapability, "wikiCapability");
        if (scopeVersion == null) {
            throw new IllegalArgumentException("scopeVersion must not be null");
        }
        adminInstructionDocumentId = requireNotBlank(
                adminInstructionDocumentId,
                "adminInstructionDocumentId"
        );
    }

    /**
     * 해당 Wiki의 관리자 대화 1건입니다.
     * senderType은 계약대로 소문자 admin·agent를 사용한다.
     */
    public record ChatMessage(String senderType, String content) {
        public ChatMessage {
            senderType = requireNotBlank(senderType, "senderType");
            content = requireNotBlank(content, "content");
        }
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
