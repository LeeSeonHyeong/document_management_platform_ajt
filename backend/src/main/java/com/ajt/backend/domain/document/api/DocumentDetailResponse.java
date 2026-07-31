package com.ajt.backend.domain.document.api;

import java.time.Instant;
import java.util.List;

/**
 * 원본문서 상세 응답입니다. 목록 필드 + 연관 Wiki + 다운로드 URL을 담는다(schema.js DocumentDetail).
 *
 * <p>참고(S15P11B106-70): 프론트 요구에 맞춘 리치 응답. Postman 계약 갱신은 팀 절차로 별도 반영 예정.
 */
public record DocumentDetailResponse(
        String documentId,
        String originalFileName,
        String mimeType,
        long fileSize,
        String documentCategoryId,
        String documentCategoryName,
        String scopeKey,
        String visibilityType,
        List<DocumentDepartmentResponse> departments,
        String status,
        String failureReason,
        DocumentUploaderResponse uploadedBy,
        Instant uploadedAt,
        String downloadUrl,
        List<RelatedWikiResponse> relatedWikis
) {

    public record RelatedWikiResponse(
            String wikiId,
            String title
    ) {
    }
}
