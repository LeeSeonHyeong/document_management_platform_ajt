package com.ajt.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로그인한 사용자가 본인 비밀번호를 변경할 때 사용하는 입력값입니다(FR-USR-013, S15P11B106-98).
 *
 * <p>필드명은 프론트 구현(`PATCH /api/v1/me/password`, `currentPassword`·`newPassword`)에 맞춘다.
 * 새 비밀번호 형식은 회원가입·재설정과 동일한 정책(8자 이상 100자 이하)을 재사용한다.
 * 대상 사용자를 지정하는 입력은 두지 않는다 — 인증 주체 본인의 계정에만 적용된다.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "현재 비밀번호는 필수입니다.")
        String currentPassword,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(min = 8, max = 100, message = "새 비밀번호는 8자 이상 100자 이하로 입력해주세요.")
        String newPassword
) {
}
