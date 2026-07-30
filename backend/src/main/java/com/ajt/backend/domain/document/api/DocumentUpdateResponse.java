package com.ajt.backend.domain.document.api;

import java.util.List;

/**
 * DOC-05 원본문서 메타데이터 수정 응답입니다.
 * 재처리 작업 ID·상태와 수정된 문서 정보를 함께 반환합니다.
 */
public record DocumentUpdateResponse(
        String jobId,
        String status,
        List<ReprocessJobResponse> reprocessJobs,
        DocumentDetailResponse document
) {

    public DocumentUpdateResponse {
        reprocessJobs = List.copyOf(reprocessJobs);
    }
}
