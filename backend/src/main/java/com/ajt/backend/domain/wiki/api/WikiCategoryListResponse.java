package com.ajt.backend.domain.wiki.api;

import com.ajt.backend.domain.wiki.model.WikiCategory;
import java.util.List;

/**
 * Wiki 카테고리 목록 응답입니다.
 * 공간 하나의 카테고리를 이름순으로 items 배열에 담아 반환합니다.
 */
public record WikiCategoryListResponse(
        List<WikiCategoryResponse> items
) {
    public static WikiCategoryListResponse from(List<WikiCategory> categories) {
        return new WikiCategoryListResponse(
                categories.stream().map(WikiCategoryResponse::from).toList()
        );
    }
}
