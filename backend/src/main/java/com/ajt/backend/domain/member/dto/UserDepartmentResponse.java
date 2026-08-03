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
        // 수정(S15P11B106-183): 명목상 부서('최고관리자')는 실제 조직 부서가 아니므로 사용자 응답에서 노출하지 않는다.
        //   판정은 Department.isNominal() 한 곳을 재사용한다. ('미지정' 기본 부서는 정상 부서라 그대로 노출)
        if (department == null || department.isNominal()) {
            return null;
        }
        return new UserDepartmentResponse(
                String.valueOf(department.getId()),
                department.getName()
        );
    }
}
