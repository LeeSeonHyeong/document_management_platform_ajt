package com.ajt.backend.domain.member.dto;

import com.ajt.backend.domain.member.Member;
import java.time.Instant;

/**
 * 관리자 가입 신청 목록의 한 줄 정보입니다.
 * 승인/거절 대상자를 고를 수 있도록 신청자와 부서, 상태를 담습니다.
 */
public record SignupRequestSummaryResponse(
        String userId,
        String email,
        String name,
        UserDepartmentResponse department,
        String signupStatus,
        Instant requestedAt
) {
    public static SignupRequestSummaryResponse from(Member member) {
        return new SignupRequestSummaryResponse(
                String.valueOf(member.getId()),
                member.getEmail(),
                member.getName(),
                UserDepartmentResponse.from(member.getDepartment()),
                member.getSignupStatus().apiValue(),
                member.getCreatedAt()
        );
    }
}
