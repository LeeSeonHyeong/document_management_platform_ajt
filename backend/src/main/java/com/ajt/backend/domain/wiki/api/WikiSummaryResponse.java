package com.ajt.backend.domain.wiki.api;

import java.time.Instant;

/**
 * Wiki 목록·검색의 한 줄 정보입니다. (WIKI-01)
 * 요약(summary)은 wiki 테이블에 컬럼이 없어 공간 목차(index.md)에서 읽어 채운다.
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
