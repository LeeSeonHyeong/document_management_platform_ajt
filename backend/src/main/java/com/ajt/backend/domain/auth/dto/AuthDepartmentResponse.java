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
        // 수정(S15P11B106-183): 명목상 부서('최고관리자')는 실제 조직 부서가 아니므로 사용자 응답에서 노출하지 않는다.
        //   판정은 Department.isNominal() 한 곳을 재사용한다. ('전체' 기본 부서는 정상 부서라 그대로 노출)
        if (department == null || department.isNominal()) {
            return null;
        }
        return new AuthDepartmentResponse(String.valueOf(department.getId()), department.getName());
    }
}
