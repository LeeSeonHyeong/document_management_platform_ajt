package com.ajt.backend.domain.inquiry.dto;

import com.ajt.backend.domain.member.Member;

/**
 * 문의 담당자(관리자) 요약 정보입니다.
 * 담당자 후보 목록과 문의 응답의 assignee 필드에서 공통으로 사용합니다.
 */
public record InquiryAssigneeResponse(
        String assigneeId,
        String name,
        InquiryDepartmentResponse department
) {
    public static InquiryAssigneeResponse from(Member member) {
        return new InquiryAssigneeResponse(
                String.valueOf(member.getId()),
                member.getName(),
                InquiryDepartmentResponse.from(member.getDepartment())
        );
    }
}
