package com.ajt.backend.domain.question.dto;

import java.util.List;

/**
 * 답변 출처입니다. {@code type}이 {@code wiki}면 {@code wikiId}와 {@code evidenceDocuments}를,
 * {@code schedule}이면 {@code scheduleId}를 채웁니다. 쓰지 않는 필드는 응답에서 생략합니다.
 */
public record QuestionAskSourceResponse(
        String type,
        String wikiId,
        String scheduleId,
        String title,
        List<QuestionEvidenceDocumentResponse> evidenceDocuments
) {
    public static QuestionAskSourceResponse wiki(
            String wikiId,
            String title,
            List<QuestionEvidenceDocumentResponse> evidenceDocuments
    ) {
        return new QuestionAskSourceResponse("wiki", wikiId, null, title, evidenceDocuments);
    }

    public static QuestionAskSourceResponse schedule(String scheduleId, String title) {
        return new QuestionAskSourceResponse("schedule", null, scheduleId, title, null);
    }
}
