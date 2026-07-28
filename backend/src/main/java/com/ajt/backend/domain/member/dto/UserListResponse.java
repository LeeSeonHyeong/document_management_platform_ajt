package com.ajt.backend.domain.member.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 관리자 사용자 목록 응답입니다.
 * items는 실제 목록, 나머지는 프론트 페이지네이션 버튼을 만들 때 쓰입니다.
 */
public record UserListResponse(
        List<UserSummaryResponse> items,
        int page,
        int size,
        long totalCount,
        int totalPages
) {
    public static UserListResponse from(Page<UserSummaryResponse> page) {
        return new UserListResponse(
                page.getContent(),
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
