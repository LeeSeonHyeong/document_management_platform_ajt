package com.ajt.backend.domain.inquiry.dto;

import com.ajt.backend.domain.member.Member;
import java.util.List;

/**
 * 문의 담당자 후보 목록 응답입니다.
 * 후보 수가 많지 않아 페이지네이션 없이 items 배열로 반환합니다.
 */
public record InquiryAssigneeListResponse(
        List<InquiryAssigneeResponse> items
) {
    public static InquiryAssigneeListResponse from(List<Member> members) {
        return new InquiryAssigneeListResponse(
                members.stream().map(InquiryAssigneeResponse::from).toList()
        );
    }
}
