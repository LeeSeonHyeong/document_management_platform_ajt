package com.ajt.backend.domain.question.dto;

/** Wiki 출처의 근거 원본문서입니다. 본문은 답변에 쓰지 않고 목록으로만 표시합니다. */
public record QuestionEvidenceDocumentResponse(
        String documentId,
        String originalFileName,
        String downloadUrl
) {
}
