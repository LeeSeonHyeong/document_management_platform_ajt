package com.ajt.backend.global.ai.client;

import java.util.List;
import java.util.Objects;

/**
 * 챗봇 답변 생성 요청입니다. (POST /internal/v1/answers)
 *
 * <p>수정(S15P11B106-169): 계약 1.8.0 에서 <b>자료 선택 단계가 없어졌다.</b> 본문과 일정 목록을
 * 싣지 않고, 에이전트가 Wiki 조회 API·일정 조회 API 로 필요한 것을 직접 읽는다. 그래서 요청이
 * 네 덩이로 끝난다 — 번호 둘, 질문, 이전 대화, 그리고 범위별 목차.
 */
public record AnswerGenerationRequest(
        String questionId,
        String conversationId,
        String question,
        List<ConversationMessage> conversationMessages,
        List<WikiIndex> wikiIndexes
) {
    public AnswerGenerationRequest {
        questionId = requireNotBlank(questionId, "questionId");
        conversationId = requireNotBlank(conversationId, "conversationId");
        question = requireNotBlank(question, "question");
        conversationMessages = List.copyOf(Objects.requireNonNull(
                conversationMessages, "conversationMessages must not be null"));
        wikiIndexes = List.copyOf(Objects.requireNonNull(wikiIndexes, "wikiIndexes must not be null"));
    }

    /** 멀티턴 문맥입니다. {@code role}은 계약상 {@code user} 또는 {@code assistant}입니다. */
    public record ConversationMessage(String role, String content) {

        public ConversationMessage {
            role = requireNotBlank(role, "role");
            content = requireNotBlank(content, "content");
        }
    }

    /**
     * 범위 하나의 목차와 <b>그 범위 조회 허가값</b>입니다.
     *
     * <p>허가값을 별도 배열로 두지 않고 이 행에 넣는다. 두 배열로 나누면 한쪽에만 있는 범위가
     * 생기고, 그러면 에이전트가 목차는 읽었는데 본문은 못 읽는 상태가 된다. 한 행에 두면 그
     * 어긋남이 애초에 불가능하다.
     *
     * <p>{@code wikiCapability}는 필수다 — 빠진 범위는 조회가 전부 실패해 「근거 없음」으로
     * 나오고 원인을 짐작하기 어렵다. 로그·오류 응답에 남기지 않는다.
     */
    public record WikiIndex(String scopeKey, String indexMarkdown, String wikiCapability) {

        public WikiIndex {
            scopeKey = requireNotBlank(scopeKey, "scopeKey");
            indexMarkdown = Objects.requireNonNull(indexMarkdown, "indexMarkdown must not be null");
            wikiCapability = requireNotBlank(wikiCapability, "wikiCapability");
        }
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
