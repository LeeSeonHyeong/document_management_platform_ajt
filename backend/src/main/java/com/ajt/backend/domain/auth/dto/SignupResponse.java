package com.ajt.backend.domain.auth.dto;

import com.ajt.backend.domain.member.Member;

/**
 * 회원가입 신청이 접수된 뒤 클라이언트에 보여줄 가입 상태입니다.
 */
public record SignupResponse(
        String userId,
        String email,
        String name,
        String role,
        String departmentId,
        String employeeNo,
        String signupStatus,
        String accountStatus
) {

    public static SignupResponse from(Member member) {
        return new SignupResponse(
                String.valueOf(member.getId()),
                member.getEmail(),
                member.getName(),
                member.getRole().apiValue(),
                String.valueOf(member.getDepartment().getId()),
                member.getEmployeeNo(),
                member.getSignupStatus().apiValue(),
                member.getAccountStatus().apiValue()
        );
    }
}
