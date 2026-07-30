package com.ajt.backend.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 인증번호로 새 비밀번호를 저장할 때 사용하는 입력값입니다.
 * 수정: 링크 토큰 방식 → 이메일 + 6자리 인증번호 방식으로 변경.
 */
public record PasswordResetConfirmRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "인증번호는 필수입니다.")
        @Pattern(regexp = "\\d{6}", message = "인증번호는 6자리 숫자입니다.")
        String code,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(min = 8, max = 100, message = "새 비밀번호는 8자 이상 100자 이하로 입력해주세요.")
        String newPassword
) {
}
