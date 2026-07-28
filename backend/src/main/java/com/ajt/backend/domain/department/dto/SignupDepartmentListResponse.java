package com.ajt.backend.domain.department.dto;

import com.ajt.backend.domain.department.Department;
import java.util.List;

/**
 * 회원가입 화면에서 선택 가능한 부서 목록입니다.
 */
public record SignupDepartmentListResponse(List<SignupDepartmentResponse> items) {

    public static SignupDepartmentListResponse from(List<Department> departments) {
        return new SignupDepartmentListResponse(
                departments.stream()
                        .map(SignupDepartmentResponse::from)
                        .toList()
        );
    }
}
