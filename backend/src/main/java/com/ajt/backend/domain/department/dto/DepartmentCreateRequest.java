package com.ajt.backend.domain.department.dto;

import jakarta.validation.constraints.Size;

/**
 * 부서 생성 요청입니다.
 * managerId가 null이면 관리자를 지정하지 않은 부서로 생성합니다.
 */
public record DepartmentCreateRequest(
        @Size(max = 50, message = "부서명은 50자 이하여야 합니다.")
        String name,
        String managerId
) {
}
