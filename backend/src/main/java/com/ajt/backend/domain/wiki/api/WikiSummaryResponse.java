package com.ajt.backend.domain.wiki.api;

import java.time.Instant;

/**
 * Wiki 목록·검색의 한 줄 정보입니다. (WIKI-01)
 * 요약(summary)은 {@code wiki.summary} 컬럼에서 채운다.
 * pageKey 는 본문 내부 링크({@code pages/{pageKey}.md})를 wikiId 로 되돌리는 열쇠다. (S15P11B106-300)
 */
public record WikiSummaryResponse(
        String wikiId,
        String pageKey,
        String title,
        String summary,
        String wikiCategoryId,
        String wikiCategoryName,
        String scopeKey,
        Instant updatedAt
) {
}
