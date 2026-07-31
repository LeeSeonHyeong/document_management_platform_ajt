package com.ajt.backend.global.ai.client;

import java.util.List;
import java.util.Objects;

/**
 * 답변 1단계 — 문맥 선택 요청입니다. (POST /internal/v1/answer-context-selections)
 *
 * <p>본문은 보내지 않는다. 목차({@code wikiIndexes})와 일정 요약({@code scheduleSummaries})만 보고
 * AI가 필요한 자료 ID를 고른다. 후보는 <b>이 사용자가 접근할 수 있는 것만</b> 담아야 한다.
 */
public record AnswerContextSelectionRequest(
        String questionId,
        String conversationId,
        String question,
        List<ConversationMessage> conversationMessages,
        List<WikiIndex> wikiIndexes,
        List<ScheduleSummary> scheduleSummaries
) {
    public AnswerContextSelectionRequest {
        questionId = requireNotBlank(questionId, "questionId");
        conversationId = requireNotBlank(conversationId, "conversationId");
        question = requireNotBlank(question, "question");
        conversationMessages = List.copyOf(Objects.requireNonNull(
                conversationMessages, "conversationMessages must not be null"));
        wikiIndexes = List.copyOf(Objects.requireNonNull(wikiIndexes, "wikiIndexes must not be null"));
        scheduleSummaries = List.copyOf(Objects.requireNonNull(
                scheduleSummaries, "scheduleSummaries must not be null"));
    }

    /** 멀티턴 문맥입니다. {@code role}은 계약상 {@code user} 또는 {@code assistant}입니다. */
    public record ConversationMessage(String role, String content) {

        public ConversationMessage {
            role = requireNotBlank(role, "role");
            content = requireNotBlank(content, "content");
        }
    }

    public record WikiIndex(String scopeKey, String indexMarkdown) {

        public WikiIndex {
            scopeKey = requireNotBlank(scopeKey, "scopeKey");
            indexMarkdown = Objects.requireNonNull(indexMarkdown, "indexMarkdown must not be null");
        }
    }

    public record ScheduleSummary(
            String scheduleId,
            String title,
            String startAt,
            String endAt,
            String targetText,
            String location
    ) {
        public ScheduleSummary {
            scheduleId = requireNotBlank(scheduleId, "scheduleId");
            title = requireNotBlank(title, "title");
            startAt = requireNotBlank(startAt, "startAt");
            endAt = requireNotBlank(endAt, "endAt");
        }
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
