package com.ajt.backend.domain.department.dto;

import com.ajt.backend.domain.department.Department;

/**
 * 회원가입 화면의 부서 선택 박스에 보여줄 부서 한 건입니다.
 */
public record SignupDepartmentResponse(
        String departmentId,
        String name
) {

    public static SignupDepartmentResponse from(Department department) {
        return new SignupDepartmentResponse(String.valueOf(department.getId()), department.getName());
    }
}
