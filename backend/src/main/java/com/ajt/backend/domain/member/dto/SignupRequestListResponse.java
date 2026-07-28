package com.ajt.backend.domain.member.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 가입 신청 목록 응답입니다.
 * 관리자가 대기/승인/거절 상태별로 신청을 확인할 때 사용합니다.
 */
public record SignupRequestListResponse(
        List<SignupRequestSummaryResponse> items,
        int page,
        int size,
        long totalCount,
        int totalPages
) {
    public static SignupRequestListResponse from(Page<SignupRequestSummaryResponse> page) {
        return new SignupRequestListResponse(
                page.getContent(),
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
