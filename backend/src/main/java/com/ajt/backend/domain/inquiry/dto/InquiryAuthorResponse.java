package com.ajt.backend.domain.inquiry.dto;

import com.ajt.backend.domain.member.Member;

/**
 * 문의 등록자(작성자) 요약 정보입니다.
 * 수정(S15P11B106-190): 작성자 소속 부서를 함께 내려, 목록/상세에서 "이름 · 부서"로 표시할 수 있게 한다.
 * 부서 구조는 assignee.department와 동일한 InquiryDepartmentResponse를 재사용한다.
 */
public record InquiryAuthorResponse(
        String userId,
        String name,
        InquiryDepartmentResponse department
) {
    public static InquiryAuthorResponse from(Member member) {
        return new InquiryAuthorResponse(
                String.valueOf(member.getId()),
                member.getName(),
                InquiryDepartmentResponse.from(member.getDepartment())
        );
    }
}
