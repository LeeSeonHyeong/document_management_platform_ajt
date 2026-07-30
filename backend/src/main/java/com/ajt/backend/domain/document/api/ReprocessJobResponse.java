package com.ajt.backend.domain.document.api;

/** 공개 범위 변경으로 재처리되는 Wiki 공간 작업입니다. */
public record ReprocessJobResponse(
        String scopeKey,
        String jobId
) {
}
