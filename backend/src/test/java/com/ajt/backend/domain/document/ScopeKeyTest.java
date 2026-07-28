package com.ajt.backend.domain.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("문서 공개 범위 키")
class ScopeKeyTest {

    @Test
    @DisplayName("전체 공개는 ALL 키를 만든다")
    void createsAllScopeKey() {
        ScopeKey scopeKey = ScopeKey.from("all", List.of());

        assertThat(scopeKey.value()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("부서 공개는 부서 ID를 중복 제거하고 오름차순으로 정렬한다")
    void createsSortedUniqueDepartmentScopeKey() {
        ScopeKey scopeKey = ScopeKey.from("department", List.of(3L, 1L, 3L, 2L));

        assertThat(scopeKey.value()).isEqualTo("D1-D2-D3");
        assertThat(scopeKey.departmentIds()).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("전체 공개에 부서 ID가 있으면 거부한다")
    void rejectsDepartmentsForAllVisibility() {
        assertThatThrownBy(() -> ScopeKey.from("all", List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("부서 공개에 부서 ID가 없으면 거부한다")
    void rejectsEmptyDepartmentsForDepartmentVisibility() {
        assertThatThrownBy(() -> ScopeKey.from("department", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("0 이하 부서 ID를 거부한다")
    void rejectsNonPositiveDepartmentId() {
        assertThatThrownBy(() -> ScopeKey.from("department", List.of(0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("알 수 없는 공개 범위를 거부한다")
    void rejectsUnknownVisibilityType() {
        assertThatThrownBy(() -> ScopeKey.from("company", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
