package com.ajt.backend.domain.member.dto;

import com.ajt.backend.domain.member.Member;
import java.time.Instant;

/**
 * 가입 신청 거절 결과입니다.
 * 거절 후에도 신청자 기록은 남기고 상태만 rejected/inactive로 바뀝니다.
 */
public record SignupRejectionResponse(
        String userId,
        String signupStatus,
        String accountStatus,
        Instant rejectedAt
) {
    public static SignupRejectionResponse from(Member member) {
        return new SignupRejectionResponse(
                String.valueOf(member.getId()),
                member.getSignupStatus().apiValue(),
                member.getAccountStatus().apiValue(),
                member.getUpdatedAt()
        );
    }
}
