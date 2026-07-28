package com.ajt.backend.domain.auth.dto;

import com.ajt.backend.domain.department.Department;

/**
 * 로그인 응답에 포함되는 최소 부서 정보입니다.
 */
public record AuthDepartmentResponse(
        String departmentId,
        String name
) {

    public static AuthDepartmentResponse from(Department department) {
        return new AuthDepartmentResponse(String.valueOf(department.getId()), department.getName());
    }
}
