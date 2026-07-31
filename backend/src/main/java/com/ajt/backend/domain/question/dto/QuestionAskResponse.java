package com.ajt.backend.domain.question.dto;

import java.time.Instant;
import java.util.List;

/** 챗봇 질문 응답입니다. 계약의 확정 Saved Example 형태를 따릅니다. */
public record QuestionAskResponse(
        String conversationId,
        String questionId,
        String questionType,
        String answer,
        List<QuestionAskSourceResponse> sources,
        Instant createdAt
) {
}
