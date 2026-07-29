package com.ajt.backend.domain.inquiry.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 문의 목록 응답입니다.
 * items는 실제 목록, 나머지는 프론트 페이지네이션에 사용합니다.
 */
public record InquiryListResponse(
        List<InquirySummaryResponse> items,
        int page,
        int size,
        long totalCount,
        int totalPages
) {
    public static InquiryListResponse from(Page<InquirySummaryResponse> page) {
        return new InquiryListResponse(
                page.getContent(),
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
