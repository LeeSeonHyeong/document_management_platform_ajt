package com.ajt.backend.domain.inquiry.dto;

import com.ajt.backend.domain.member.Member;

/**
 * 문의 등록자(작성자) 요약 정보입니다.
 */
public record InquiryAuthorResponse(
        String userId,
        String name
) {
    public static InquiryAuthorResponse from(Member member) {
        return new InquiryAuthorResponse(
                String.valueOf(member.getId()),
                member.getName()
        );
    }
}
