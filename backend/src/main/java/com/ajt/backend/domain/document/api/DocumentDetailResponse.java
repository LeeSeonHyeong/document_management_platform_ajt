package com.ajt.backend.domain.document.api;

import java.time.Instant;
import java.util.List;

public record DocumentDetailResponse(
        String documentId,
        String originalFileName,
        String scopeKey,
        CategoryResponse category,
        String status,
        String failureReason,
        String downloadUrl,
        List<RelatedWikiResponse> relatedWikis,
        Instant createdAt,
        Instant updatedAt
) {

    public record CategoryResponse(
            String documentCategoryId,
            String name
    ) {
    }

    public record RelatedWikiResponse(
            String wikiId,
            String title
    ) {
    }
}
