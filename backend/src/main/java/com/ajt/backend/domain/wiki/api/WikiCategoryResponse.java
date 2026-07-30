package com.ajt.backend.domain.wiki.api;

import com.ajt.backend.domain.wiki.model.WikiCategory;

/**
 * Wiki 카테고리 한 건의 응답입니다.
 * 계약의 {@code GET /api/v1/wiki-categories} 응답 형태를 따릅니다.
 */
public record WikiCategoryResponse(
        String wikiCategoryId,
        String scopeKey,
        String name,
        String description
) {
    public static WikiCategoryResponse from(WikiCategory category) {
        return new WikiCategoryResponse(
                String.valueOf(category.id()),
                category.scopeKey(),
                category.name(),
                category.description()
        );
    }
}
