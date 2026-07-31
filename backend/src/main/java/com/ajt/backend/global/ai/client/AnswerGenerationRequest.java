package com.ajt.backend.global.ai.client;

import java.util.List;
import java.util.Objects;

/**
 * 답변 2단계 — 답변 생성 요청입니다. (POST /internal/v1/answers)
 *
 * <p>Spring Boot가 1단계 선택 ID의 권한을 재검증하고 읽어 온 본문만 담는다. 권한이 없어 걸러진
 * 자료는 여기에 실리지 않으므로 답변과 출처에도 나타나지 않는다.
 */
public record AnswerGenerationRequest(
        String questionId,
        String conversationId,
        String questionType,
        String question,
        List<AnswerContextSelectionRequest.ConversationMessage> conversationMessages,
        List<SelectedWiki> selectedWikis,
        List<SelectedSchedule> selectedSchedules
) {
    public AnswerGenerationRequest {
        questionId = requireNotBlank(questionId, "questionId");
        conversationId = requireNotBlank(conversationId, "conversationId");
        questionType = requireNotBlank(questionType, "questionType");
        question = requireNotBlank(question, "question");
        conversationMessages = List.copyOf(Objects.requireNonNull(
                conversationMessages, "conversationMessages must not be null"));
        selectedWikis = List.copyOf(Objects.requireNonNull(selectedWikis, "selectedWikis must not be null"));
        selectedSchedules = List.copyOf(Objects.requireNonNull(
                selectedSchedules, "selectedSchedules must not be null"));
    }

    public record SelectedWiki(String wikiId, String title, String contentMarkdown) {

        public SelectedWiki {
            wikiId = requireNotBlank(wikiId, "wikiId");
            title = requireNotBlank(title, "title");
            contentMarkdown = requireNotBlank(contentMarkdown, "contentMarkdown");
        }
    }

    public record SelectedSchedule(
            String scheduleId,
            String title,
            String content,
            String startAt,
            String endAt,
            String targetText,
            String location
    ) {
        public SelectedSchedule {
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
