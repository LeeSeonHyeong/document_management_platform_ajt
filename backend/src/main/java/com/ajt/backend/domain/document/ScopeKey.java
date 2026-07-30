package com.ajt.backend.domain.document;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

public record ScopeKey(String value, List<Long> departmentIds) {

    private static final String ALL_VISIBILITY = "all";
    private static final String DEPARTMENT_VISIBILITY = "department";
    private static final String DEPARTMENT_SCOPE_PATTERN = "D\\d+(?:-D\\d+)*";

    public ScopeKey {
        departmentIds = List.copyOf(departmentIds);
    }

    /**
     * 저장된 scope_key 문자열("ALL" 또는 "D1-D2")을 해석한다. "D1-D2" 파싱 규칙의 단일 출처.
     * 형식 오류·비정규(중복/미정렬) 값은 IllegalArgumentException으로 거부한다(호출부에서 도메인 예외로 변환).
     */
    public static ScopeKey parse(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) {
            throw new IllegalArgumentException("scope_key는 필수입니다.");
        }
        if ("ALL".equals(scopeKey)) {
            return new ScopeKey("ALL", List.of());
        }
        if (!scopeKey.matches(DEPARTMENT_SCOPE_PATTERN)) {
            throw new IllegalArgumentException("지원하지 않는 scope_key 형식입니다: " + scopeKey);
        }
        List<Long> ids = Arrays.stream(scopeKey.split("-"))
                .map(token -> Long.valueOf(token.substring(1)))
                .toList();
        ScopeKey canonical = from(DEPARTMENT_VISIBILITY, ids);
        if (!canonical.value().equals(scopeKey)) {
            throw new IllegalArgumentException("정규화되지 않은 scope_key입니다: " + scopeKey);
        }
        return canonical;
    }

    public boolean isAll() {
        return "ALL".equals(value);
    }

    /** 프론트 계약의 공개 유형 값(all/department)으로 표현한다. */
    public String visibilityType() {
        return isAll() ? ALL_VISIBILITY : DEPARTMENT_VISIBILITY;
    }

    public static ScopeKey from(String visibilityType, Collection<Long> departmentIds) {
        List<Long> ids = departmentIds == null ? List.of() : List.copyOf(departmentIds);

        if (ALL_VISIBILITY.equals(visibilityType)) {
            if (!ids.isEmpty()) {
                throw new IllegalArgumentException("전체 공개에는 부서 ID를 지정할 수 없습니다.");
            }
            return new ScopeKey("ALL", List.of());
        }

        if (DEPARTMENT_VISIBILITY.equals(visibilityType)) {
            TreeSet<Long> sortedIds = new TreeSet<>(ids);
            if (sortedIds.isEmpty() || sortedIds.first() <= 0) {
                throw new IllegalArgumentException("부서 공개에는 양수 부서 ID가 하나 이상 필요합니다.");
            }
            List<Long> sortedDepartmentIds = List.copyOf(sortedIds);

            return new ScopeKey(sortedDepartmentIds.stream()
                    .map(id -> "D" + id)
                    .reduce((left, right) -> left + "-" + right)
                    .orElseThrow(), sortedDepartmentIds);
        }

        throw new IllegalArgumentException("지원하지 않는 공개 범위입니다.");
    }
}
