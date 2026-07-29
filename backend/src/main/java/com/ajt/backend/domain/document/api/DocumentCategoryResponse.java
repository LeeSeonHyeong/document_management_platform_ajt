package com.ajt.backend.domain.document.api;

import com.ajt.backend.domain.document.model.DocumentCategory;

/**
 * CAT-01~03 응답 DTO입니다.
 * DB의 숫자 ID는 프론트에서 안전하게 쓰기 쉽도록 문자열로 내려줍니다.
 */
public record DocumentCategoryResponse(
        String categoryId,
        String scopeKey,
        String name,
        String description
) {

    public static DocumentCategoryResponse from(DocumentCategory category) {
        return new DocumentCategoryResponse(
                String.valueOf(category.id()),
                category.scopeKey(),
                category.name(),
                category.description()
        );
    }
}
