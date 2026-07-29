package com.ajt.backend.domain.document.api;

/**
 * DOC-05 원본문서 삭제 응답입니다.
 * 삭제 후 해당 공개 범위(scopeKey) Wiki 재처리 작업 정보를 반환합니다.
 */
public record DocumentDeleteResponse(
        String jobId,
        String scopeKey,
        String status
) {
}
