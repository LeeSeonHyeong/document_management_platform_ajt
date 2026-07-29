package com.ajt.backend.domain.document.api;

import com.ajt.backend.domain.document.model.DocumentCategory;
import java.util.List;

/**
 * CAT-01 문서 카테고리 목록 응답입니다.
 * items 안에 조회된 카테고리들을 담아 프론트로 전달합니다.
 */
public record DocumentCategoryListResponse(
        List<DocumentCategoryResponse> items
) {

    public static DocumentCategoryListResponse from(List<DocumentCategory> categories) {
        return new DocumentCategoryListResponse(
                categories.stream()
                        .map(DocumentCategoryResponse::from)
                        .toList()
        );
    }
}
