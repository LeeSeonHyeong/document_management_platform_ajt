package com.ajt.backend.domain.document.api;

import com.ajt.backend.domain.document.model.Document;
import java.time.Instant;
import java.util.List;

/**
 * 원본문서 목록의 한 줄 정보입니다. (관리자 문서 관리 목록 화면용)
 * 프론트 계약(schema.js DocumentListItem)에 맞춰 카테고리·공개범위·업로더를 평탄한 필드로 내려준다.
 *
 * <p>참고(S15P11B106-70): 이 리치 응답 모양은 프론트 요구에 맞춘 것으로, 현재 Postman 계약의
 * 목록 응답 서술(문서ID·파일명·scopeKey·카테고리·상태·업로드자·시각)보다 넓다.
 * 계약(docs/api) 갱신은 팀 계약 변경 절차로 별도 반영 예정. (코드 선반영)
 */
public record DocumentSummaryResponse(
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
        // 이 문서를 근거로 삼고 있는 Wiki 수입니다(S15P11B106-306).
        // 「지금 Wiki 의 근거인가」를 목록이 판단하는 값이다 — 삭제·교체가 실패한 문서는 status 가
        // FAILED 여도 근거로는 그대로 남아 있어서, 상태만으로는 감출지 보일지 정할 수 없다.
        int wikiCount,
        DocumentUploaderResponse uploadedBy,
        Instant uploadedAt
) {

    /**
     * 카테고리명·공개범위(부서 목록)·업로더는 호출부에서 페이지 단위로 한 번에 조회해 넘겨준다(N+1 방지).
     */
    public static DocumentSummaryResponse from(
            Document document,
            String documentCategoryName,
            String visibilityType,
            List<DocumentDepartmentResponse> departments,
            DocumentUploaderResponse uploadedBy
    ) {
        return new DocumentSummaryResponse(
                String.valueOf(document.id()),
                document.originalFileName(),
                document.mimeType(),
                document.fileSize(),
                // 확정 전 업로드는 카테고리가 없다. String.valueOf(null)은 문자열 "null"을 만들어
                // 프론트가 값이 있는 것으로 읽는다 — 그러면 분류가 끝난 문서로 보인다.
                toStringOrNull(document.documentCategoryId()),
                documentCategoryName,
                document.scopeKey(),
                visibilityType,
                departments,
                document.status().name().toLowerCase(),
                document.failureReason(),
                document.documentWikiRefs().size(),
                uploadedBy,
                document.createdAt()
        );
    }

    private static String toStringOrNull(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
