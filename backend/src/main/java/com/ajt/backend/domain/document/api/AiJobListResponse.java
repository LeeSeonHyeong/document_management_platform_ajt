package com.ajt.backend.domain.document.api;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * AI 작업 이력 목록입니다. 항목 모양은 단건 조회와 같은 {@link AiJobResponse}를 씁니다.
 *
 * <p>관리자 화면의 「요약 목록」이 작업 회차별로 묶여 있고, 회차 안에서 문서별 변경 요약을
 * 펼치기 때문에 목록 전용 축약형을 따로 두지 않습니다. 두 응답이 갈라지면 같은 화면이
 * 어디서 받았느냐에 따라 다른 필드를 보게 됩니다.
 */
public record AiJobListResponse(
        List<AiJobResponse> items,
        int page,
        int size,
        long totalCount,
        int totalPages
) {
    public static AiJobListResponse from(Page<AiJobResponse> page) {
        return new AiJobListResponse(
                page.getContent(),
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
