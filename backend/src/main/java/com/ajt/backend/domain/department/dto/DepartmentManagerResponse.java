package com.ajt.backend.domain.department.dto;

import com.ajt.backend.domain.member.Member;

/**
 * 부서 응답에 포함되는 관리자 요약 정보입니다.
 * 화면에서는 관리자 ID와 이름만 있으면 담당자를 표시할 수 있습니다.
 */
public record DepartmentManagerResponse(
        String userId,
        String name
) {
    public static DepartmentManagerResponse from(Member manager) {
        if (manager == null) {
            return null;
        }
        return new DepartmentManagerResponse(String.valueOf(manager.getId()), manager.getName());
    }
}
