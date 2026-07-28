package com.ajt.backend.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 신청에 필요한 입력값입니다.
 * 사번은 사용자가 입력하지 않고, 관리자 승인 시 시스템에서 생성합니다.
 */
public record SignupRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 100자 이하로 입력해주세요.")
        String password,

        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 50, message = "이름은 50자 이하로 입력해주세요.")
        String name,

        @NotBlank(message = "부서는 필수입니다.")
        @Pattern(regexp = "\\d+", message = "부서 ID는 숫자 문자열이어야 합니다.")
        String departmentId
) {
}
