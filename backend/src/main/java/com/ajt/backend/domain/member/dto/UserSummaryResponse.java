package com.ajt.backend.domain.member.dto;

import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.Role;
import java.time.Instant;

/**
 * 관리자 사용자 목록의 한 줄 정보입니다.
 * 목록 화면에서 빠르게 훑어볼 수 있는 값만 담습니다.
 */
public record UserSummaryResponse(
        String userId,
        String email,
        String name,
        String employeeNo,
        String role,
        UserDepartmentResponse department,
        String signupStatus,
        String accountStatus,
        boolean isDepartmentManager,
        Instant createdAt
) {
    public static UserSummaryResponse from(Member member) {
        return new UserSummaryResponse(
                String.valueOf(member.getId()),
                member.getEmail(),
                member.getName(),
                member.getEmployeeNo(),
                member.getRole().apiValue(),
                UserDepartmentResponse.from(member.getDepartment()),
                member.getSignupStatus().apiValue(),
                member.getAccountStatus().apiValue(),
                member.getRole() == Role.ADMIN,
                member.getCreatedAt()
        );
    }
}
