package com.ajt.backend.domain.question.dto;

/**
 * 챗봇 질문 요청입니다. (POST /api/v1/questions)
 *
 * <p>{@code conversationId}가 없으면 새 대화이며 서버가 발급합니다.
 */
public record QuestionAskRequest(
        String conversationId,
        String question
) {
}
