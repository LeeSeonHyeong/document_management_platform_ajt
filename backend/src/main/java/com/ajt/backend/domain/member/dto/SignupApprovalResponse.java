package com.ajt.backend.domain.member.dto;

import com.ajt.backend.domain.member.Member;
import java.time.Instant;

/**
 * 가입 신청 승인 결과입니다.
 * 승인되면 시스템이 만든 사번과 활성 상태를 바로 확인할 수 있습니다.
 */
public record SignupApprovalResponse(
        String userId,
        String employeeNo,
        String signupStatus,
        String accountStatus,
        Instant approvedAt
) {
    public static SignupApprovalResponse from(Member member) {
        return new SignupApprovalResponse(
                String.valueOf(member.getId()),
                member.getEmployeeNo(),
                member.getSignupStatus().apiValue(),
                member.getAccountStatus().apiValue(),
                member.getUpdatedAt()
        );
    }
}
