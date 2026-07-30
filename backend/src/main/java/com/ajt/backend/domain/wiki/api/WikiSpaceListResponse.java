package com.ajt.backend.domain.wiki.api;

import java.util.List;

/**
 * Wiki 공간 목록 응답입니다.
 * 접근 가능한 공간 수가 많지 않아 페이지네이션 없이 items 배열로 반환합니다.
 */
public record WikiSpaceListResponse(
        List<WikiSpaceResponse> items
) {
}
