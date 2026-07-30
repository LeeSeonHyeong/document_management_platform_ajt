package com.ajt.backend.domain.document.api;

/**
 * 문서 공개 범위(부서) 표시에 사용하는 부서 요약입니다.
 * 공개 범위가 부서일 때 scope_key의 부서 ID를 이름과 함께 내려줍니다.
 */
public record DocumentDepartmentResponse(
        String departmentId,
        String name
) {
}
