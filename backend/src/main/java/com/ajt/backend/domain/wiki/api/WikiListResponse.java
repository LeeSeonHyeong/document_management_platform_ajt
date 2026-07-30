package com.ajt.backend.domain.wiki.api;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Wiki 목록·검색 응답입니다. (페이지네이션 포함, WIKI-01)
 */
public record WikiListResponse(
        List<WikiSummaryResponse> items,
        int page,
        int size,
        long totalCount,
        int totalPages
) {
    public static WikiListResponse from(Page<WikiSummaryResponse> page) {
        return new WikiListResponse(
                page.getContent(),
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
