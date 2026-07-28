package com.ajt.backend.domain.member.dto;

import com.ajt.backend.domain.department.Department;

/**
 * 사용자 응답 안에 들어가는 부서 요약 정보입니다.
 * 화면에서는 부서 ID와 이름만 있으면 소속 부서를 표시할 수 있습니다.
 */
public record UserDepartmentResponse(
        String departmentId,
        String name
) {
    public static UserDepartmentResponse from(Department department) {
        return new UserDepartmentResponse(
                String.valueOf(department.getId()),
                department.getName()
        );
    }
}
