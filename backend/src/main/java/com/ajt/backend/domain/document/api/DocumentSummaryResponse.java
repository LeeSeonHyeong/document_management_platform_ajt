package com.ajt.backend.domain.document.api;

import com.ajt.backend.domain.document.model.Document;
import java.time.Instant;

/**
 * 원본문서 목록의 한 줄 정보입니다. (관리자 문서 관리 목록 화면용)
 */
public record DocumentSummaryResponse(
        String documentId,
        String originalFileName,
        String scopeKey,
        CategoryResponse category,
        String status,
        String uploaderId,
        Instant createdAt
) {

    public record CategoryResponse(String documentCategoryId, String name) {
    }

    /** 카테고리명은 호출부에서 한 번에 조회해 넘겨준다(문서별 개별 조회 시 N+1 방지). */
    public static DocumentSummaryResponse from(Document document, String categoryName) {
        return new DocumentSummaryResponse(
                String.valueOf(document.id()),
                document.originalFileName(),
                document.scopeKey(),
                new CategoryResponse(String.valueOf(document.documentCategoryId()), categoryName),
                document.status().name().toLowerCase(),
                String.valueOf(document.uploaderId()),
                document.createdAt()
        );
    }
}
