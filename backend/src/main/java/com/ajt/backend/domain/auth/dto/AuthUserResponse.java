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
        String accountStatus,
        // 수정(S15P11B106-83): 최고관리자 여부. 프론트는 role=admin이 아니라 이 값으로 사용자 관리 권한을 판단한다.
        boolean isSuperAdmin,
        // 장으로 지정된 부서 ID(S15P11B106-289). 부서장이 아니면 null이며 소속 부서와 다를 수 있다.
        // 화면이 공개 범위 선택을 제한할 때 쓴다.
        String managedDepartmentId
) {

    public static AuthUserResponse from(Member member, boolean isSuperAdmin) {
        return from(member, isSuperAdmin, null);
    }

    public static AuthUserResponse from(Member member, boolean isSuperAdmin, Long managedDepartmentId) {
        return new AuthUserResponse(
                String.valueOf(member.getId()),
                member.getEmail(),
                member.getName(),
                member.getEmployeeNo(),
                member.getRole().apiValue(),
                AuthDepartmentResponse.from(member.getDepartment()),
                member.getSignupStatus().apiValue(),
                member.getAccountStatus().apiValue(),
                isSuperAdmin,
                managedDepartmentId == null ? null : String.valueOf(managedDepartmentId)
        );
    }
}
