package com.ajt.backend.domain.department.dto;

import com.ajt.backend.domain.department.Department;

/**
 * 부서 관리 화면에서 사용하는 부서 한 건의 응답입니다.
 * 관리자가 지정되지 않은 부서는 manager를 null로 내려줍니다.
 */
public record DepartmentResponse(
        String departmentId,
        String name,
        DepartmentManagerResponse manager
) {
    public static DepartmentResponse from(Department department) {
        return new DepartmentResponse(
                String.valueOf(department.getId()),
                department.getName(),
                DepartmentManagerResponse.from(department.getManager())
        );
    }
}
