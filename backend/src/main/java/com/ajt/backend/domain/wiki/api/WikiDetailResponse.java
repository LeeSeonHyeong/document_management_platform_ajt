package com.ajt.backend.domain.wiki.api;

import java.time.Instant;
import java.util.List;

/**
 * Wiki 상세 응답입니다. 계약의 {@code GET /api/v1/wikis/{wikiId}} 확정 Saved Example 형태를 따릅니다.
 *
 * <p>관리자 대화 전송 응답의 {@code updatedWiki}로도 그대로 사용합니다.
 * {@code GET /api/v1/wikis/{wikiId}}를 구현할 때 별도로 정의하지 말고 이 레코드를 재사용한다.
 */
public record WikiDetailResponse(
        String wikiId,
        String title,
        String contentMarkdown,
        Category category,
        String scopeKey,
        List<EvidenceDocument> evidenceDocuments,
        List<RelatedWiki> relatedWikis,
        Instant updatedAt
) {

    public record Category(String wikiCategoryId, String name) {
    }

    public record EvidenceDocument(String documentId, String originalFileName, String downloadUrl) {

        public static EvidenceDocument of(long documentId, String originalFileName) {
            return new EvidenceDocument(
                    String.valueOf(documentId),
                    originalFileName,
                    "/api/v1/documents/%d/file".formatted(documentId)
            );
        }
    }

    /**
     * pageKey 는 본문 내부 링크({@code pages/{pageKey}.md})를 wikiId 로 되돌리는 열쇠다. (S15P11B106-300)
     */
    public record RelatedWiki(String wikiId, String pageKey, String title) {
    }
}
