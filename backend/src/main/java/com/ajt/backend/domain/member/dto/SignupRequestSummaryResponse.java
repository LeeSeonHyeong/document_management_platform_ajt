package com.ajt.backend.domain.member.dto;

import com.ajt.backend.domain.member.Member;
import java.time.Instant;

/**
 * 관리자 가입 신청 목록의 한 줄 정보입니다.
 * 승인/거절 대상자를 고를 수 있도록 신청자와 부서, 상태를 담습니다.
 *
 * <p>수정(S15P11B106-72): 승인 완료 탭에서 사번이 보이도록 employeeNo를 추가한다.
 * 사번은 가입 승인 시에만 발급되므로 대기(PENDING)·거부(REJECTED) 신청은 값이 null이며, 화면에서 `-`로 표시한다.
 */
public record SignupRequestSummaryResponse(
        String userId,
        String email,
        String name,
        String employeeNo,
        UserDepartmentResponse department,
        String signupStatus,
        Instant requestedAt
) {
    public static SignupRequestSummaryResponse from(Member member) {
        return new SignupRequestSummaryResponse(
                String.valueOf(member.getId()),
                member.getEmail(),
                member.getName(),
                member.getEmployeeNo(),
                UserDepartmentResponse.from(member.getDepartment()),
                member.getSignupStatus().apiValue(),
                member.getCreatedAt()
        );
    }
}
