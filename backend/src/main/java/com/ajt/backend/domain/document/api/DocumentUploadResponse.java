package com.ajt.backend.domain.document.api;

import java.time.Instant;
import java.util.List;

/**
 * 원본문서 업로드 응답입니다.
 *
 * <p>업로드는 AI 작업을 만들지 않으므로 jobId가 없다(S15P11B106-276). {@code status}는 업로드된
 * 문서의 상태(uploaded)이며, 작업 생성·시작은 {@code POST /ai-jobs}가 담당한다.
 */
public record DocumentUploadResponse(
        List<String> documentIds,
        String scopeKey,
        String status,
        Instant createdAt
) {
}
