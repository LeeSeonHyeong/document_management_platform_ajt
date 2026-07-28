package com.ajt.backend.domain.department.dto;

import com.ajt.backend.domain.department.Department;
import java.util.List;

/**
 * 부서 목록 조회 응답입니다.
 * 부서는 많지 않은 기준 데이터라 페이지 없이 items 배열로만 반환합니다.
 */
public record DepartmentListResponse(
        List<DepartmentResponse> items
) {
    public static DepartmentListResponse from(List<Department> departments) {
        return new DepartmentListResponse(departments.stream()
                .map(DepartmentResponse::from)
                .toList());
    }
}
