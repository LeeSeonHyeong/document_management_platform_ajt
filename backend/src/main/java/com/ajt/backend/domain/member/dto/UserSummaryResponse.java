package com.ajt.backend.domain.member.dto;

import com.ajt.backend.domain.member.Member;
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
    // 수정: from(Member) → from(Member, boolean)으로 변경. 부서장 여부를 인자로 받는다.
    //       (역할로 부서장을 판단하던 로직을 제거하고, 실제 부서장 지정 여부를 호출부에서 넘겨받도록 함)
    public static UserSummaryResponse from(Member member, boolean isDepartmentManager) {
        return new UserSummaryResponse(
                String.valueOf(member.getId()),
                member.getEmail(),
                member.getName(),
                member.getEmployeeNo(),
                member.getRole().apiValue(),
                UserDepartmentResponse.from(member.getDepartment()),
                member.getSignupStatus().apiValue(),
                member.getAccountStatus().apiValue(),
                // 수정: 기존 (member.getRole() == Role.ADMIN)을 제거하고, 실제 부서장 지정 여부로 대체.
                //       전체 관리자·부서 관리자는 같은 ADMIN 역할이지만, 부서장은 department.manager_id로
                //       지정된 사람만 해당한다(FR-USR-006).
                isDepartmentManager,
                member.getCreatedAt()
        );
    }
}
