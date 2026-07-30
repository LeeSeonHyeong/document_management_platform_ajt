package com.ajt.backend.domain.document.api;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 관리자 원본문서 목록 응답입니다. (페이지네이션 포함)
 */
public record DocumentListResponse(
        List<DocumentSummaryResponse> items,
        int page,
        int size,
        long totalCount,
        int totalPages
) {
    public static DocumentListResponse from(Page<DocumentSummaryResponse> page) {
        return new DocumentListResponse(
                page.getContent(),
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
