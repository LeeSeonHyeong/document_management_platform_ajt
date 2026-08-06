package com.ajt.backend.domain.wiki.api;

import java.time.Instant;

/**
 * Wiki 목록·검색의 한 줄 정보입니다. (WIKI-01)
 * 요약(summary)은 {@code wiki.summary} 컬럼에서 채운다.
 */
public record WikiSummaryResponse(
        String wikiId,
        String title,
        String summary,
        String wikiCategoryId,
        String wikiCategoryName,
        String scopeKey,
        Instant updatedAt
) {
}
