package com.ajt.backend.domain.document;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

public record ScopeKey(String value) {

    private static final String ALL_VISIBILITY = "all";
    private static final String DEPARTMENT_VISIBILITY = "department";

    public static ScopeKey from(String visibilityType, Collection<Long> departmentIds) {
        List<Long> ids = departmentIds == null ? List.of() : List.copyOf(departmentIds);

        if (ALL_VISIBILITY.equals(visibilityType)) {
            if (!ids.isEmpty()) {
                throw new IllegalArgumentException("전체 공개에는 부서 ID를 지정할 수 없습니다.");
            }
            return new ScopeKey("ALL");
        }

        if (DEPARTMENT_VISIBILITY.equals(visibilityType)) {
            TreeSet<Long> sortedIds = new TreeSet<>(ids);
            if (sortedIds.isEmpty() || sortedIds.first() <= 0) {
                throw new IllegalArgumentException("부서 공개에는 양수 부서 ID가 하나 이상 필요합니다.");
            }

            return new ScopeKey(sortedIds.stream()
                    .map(id -> "D" + id)
                    .reduce((left, right) -> left + "-" + right)
                    .orElseThrow());
        }

        throw new IllegalArgumentException("지원하지 않는 공개 범위입니다.");
    }
}
