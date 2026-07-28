package com.ajt.backend.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 비밀번호 재설정 안내를 받을 이메일입니다.
 */
public record PasswordResetRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email
) {
}
