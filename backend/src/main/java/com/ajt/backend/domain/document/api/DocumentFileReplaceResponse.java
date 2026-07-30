package com.ajt.backend.domain.document.api;

/**
 * 원본문서 파일 교체(PUT /api/v1/documents/{documentId}/file) 응답.
 * 교체 후 재처리 작업을 시작하고 그 작업 식별자를 반환한다(202 Accepted).
 */
public record DocumentFileReplaceResponse(
        String jobId,
        String documentId,
        String status
) {
}
