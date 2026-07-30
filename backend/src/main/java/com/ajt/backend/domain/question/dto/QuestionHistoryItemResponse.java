package com.ajt.backend.domain.question.dto;

import com.ajt.backend.domain.question.AiAnswer;
import com.ajt.backend.domain.question.AiQuestion;
import com.ajt.backend.domain.question.AnswerSource;
import java.time.Instant;
import java.util.List;

/**
 * 질문 이력 항목 응답입니다.
 * 실패한 질문은 answer가 null이고 sources가 비어 있습니다.
 */
public record QuestionHistoryItemResponse(
        String conversationId,
        String questionId,
        String questionType,
        String question,
        String answer,
        List<QuestionSourceResponse> sources,
        Instant createdAt
) {
    public static QuestionHistoryItemResponse of(AiQuestion question, AiAnswer answer, List<AnswerSource> sources) {
        return new QuestionHistoryItemResponse(
                question.getConversationKey(),
                String.valueOf(question.getId()),
                question.getQuestionType() == null ? null : question.getQuestionType().apiValue(),
                question.getContent(),
                answer == null ? null : answer.getContent(),
                sources.stream().map(QuestionSourceResponse::from).toList(),
                question.getCreatedAt()
        );
    }
}
