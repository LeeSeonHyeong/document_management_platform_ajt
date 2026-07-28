package com.ajt.backend.domain.member.dto;

import jakarta.validation.constraints.Size;

/**
 * 관리자가 사용자 정보를 수정할 때 받는 요청입니다.
 * 값이 null이면 해당 항목은 변경하지 않는다는 뜻입니다.
 */
public record UserUpdateRequest(
        @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
        String name,
        String role,
        String departmentId,
        String accountStatus
) {
}
