package com.ajt.backend.domain.auth.dto;

import com.ajt.backend.domain.member.Member;

/**
 * 인증 API 응답에서 공통으로 사용하는 회원 요약 정보입니다.
 */
public record AuthUserResponse(
        String userId,
        String email,
        String name,
        String employeeNo,
        String role,
        AuthDepartmentResponse department,
        String signupStatus,
        String accountStatus
) {

    public static AuthUserResponse from(Member member) {
        return new AuthUserResponse(
                String.valueOf(member.getId()),
                member.getEmail(),
                member.getName(),
                member.getEmployeeNo(),
                member.getRole().apiValue(),
                AuthDepartmentResponse.from(member.getDepartment()),
                member.getSignupStatus().apiValue(),
                member.getAccountStatus().apiValue()
        );
    }
}
