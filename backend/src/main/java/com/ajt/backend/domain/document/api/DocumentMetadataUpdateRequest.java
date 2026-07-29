package com.ajt.backend.domain.document.api;

import java.util.List;

/**
 * DOC-05 원본문서 메타데이터 수정 요청입니다.
 * 카테고리와 공개 범위(전체/부서)를 바꿉니다. 세 필드 모두 필수입니다.
 */
public record DocumentMetadataUpdateRequest(
        Long documentCategoryId,
        String visibilityType,
        List<Long> departmentIds
) {
    public List<Long> departmentIds() {
        return departmentIds == null ? List.of() : departmentIds;
    }
}
