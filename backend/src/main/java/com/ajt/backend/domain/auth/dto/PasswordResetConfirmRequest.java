package com.ajt.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 재설정 토큰으로 새 비밀번호를 저장할 때 사용하는 입력값입니다.
 */
public record PasswordResetConfirmRequest(
        @NotBlank(message = "비밀번호 재설정 토큰은 필수입니다.")
        String token,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(min = 8, max = 100, message = "새 비밀번호는 8자 이상 100자 이하로 입력해주세요.")
        String newPassword
) {
}
