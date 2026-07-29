package com.ajt.backend.domain.inquiry.dto;

import com.ajt.backend.domain.department.Department;

/**
 * 담당자 응답 안에 들어가는 소속 부서 요약 정보입니다.
 */
public record InquiryDepartmentResponse(
        String departmentId,
        String name
) {
    public static InquiryDepartmentResponse from(Department department) {
        return new InquiryDepartmentResponse(
                String.valueOf(department.getId()),
                department.getName()
        );
    }
}
