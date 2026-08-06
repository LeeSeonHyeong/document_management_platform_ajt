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
        // 수정(S15P11B106-83): 최고관리자 여부. true면 사용자 관리·가입 승인/거절 권한이 있다.
        //   role=admin만으로는 부서관리자와 구분되지 않으므로 프론트는 이 값으로 권한을 판단한다.
        boolean isSuperAdmin,
        /**
         * 이 사용자가 장으로 지정된 부서 ID입니다. 부서장이 아니면 null입니다(S15P11B106-289).
         *
         * <p>소속 부서({@code department})와 다를 수 있어 따로 내려준다. 화면이 공개 범위 선택을
         * 제한할 때 이 값을 쓴다 — 소속 부서로 추측하면 담당≠소속인 부서장에게 틀린 선택지를
         * 보여주고, 서버가 403으로 거절해 「고를 수 있는데 안 되는」 상태가 된다.
         */
        String managedDepartmentId,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserResponse from(Member member, boolean isSuperAdmin) {
        return from(member, isSuperAdmin, null);
    }

    public static UserResponse from(Member member, boolean isSuperAdmin, Long managedDepartmentId) {
        return new UserResponse(
                String.valueOf(member.getId()),
                member.getEmail(),
                member.getName(),
                member.getEmployeeNo(),
                member.getRole().apiValue(),
                UserDepartmentResponse.from(member.getDepartment()),
                member.getSignupStatus().apiValue(),
                member.getAccountStatus().apiValue(),
                isSuperAdmin,
                managedDepartmentId == null ? null : String.valueOf(managedDepartmentId),
                member.getCreatedAt(),
                member.getUpdatedAt()
        );
    }
}
