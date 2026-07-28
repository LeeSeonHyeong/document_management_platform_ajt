package com.ajt.backend.domain.member.dto;

import com.ajt.backend.domain.member.Member;
import java.time.Instant;

/**
 * /me, 사용자 수정 응답에서 쓰는 회원 상세 정보입니다.
 * 비밀번호 같은 민감한 값은 절대 밖으로 내보내지 않습니다.
 */
public record UserResponse(
        String userId,
        String email,
        String name,
        String employeeNo,
        String role,
        UserDepartmentResponse department,
        String signupStatus,
        String accountStatus,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserResponse from(Member member) {
        return new UserResponse(
                String.valueOf(member.getId()),
                member.getEmail(),
                member.getName(),
                member.getEmployeeNo(),
                member.getRole().apiValue(),
                UserDepartmentResponse.from(member.getDepartment()),
                member.getSignupStatus().apiValue(),
                member.getAccountStatus().apiValue(),
                member.getCreatedAt(),
                member.getUpdatedAt()
        );
    }
}
