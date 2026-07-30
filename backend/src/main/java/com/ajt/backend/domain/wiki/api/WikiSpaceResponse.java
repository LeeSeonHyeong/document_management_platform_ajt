package com.ajt.backend.domain.wiki.api;

import java.util.List;

/**
 * Wiki 공간(scope) 한 건의 요약 응답입니다.
 * 계약의 {@code GET /api/v1/wiki-spaces} 응답 형태를 따릅니다.
 */
public record WikiSpaceResponse(
        String scopeKey,
        String visibilityType,
        List<Department> departments,
        String displayName,
        long wikiCount
) {

    public record Department(String departmentId, String name) {
    }
}
