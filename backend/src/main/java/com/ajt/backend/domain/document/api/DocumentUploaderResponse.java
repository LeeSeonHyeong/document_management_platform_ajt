package com.ajt.backend.domain.document.api;

/**
 * 문서 응답에 포함되는 업로더 요약입니다.
 * 프론트는 업로더 ID와 이름만 있으면 표시할 수 있습니다.
 */
public record DocumentUploaderResponse(
        String userId,
        String name
) {
}
