package com.ajt.backend.domain.document.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Wiki 공개 범위")
class WikiScopeTest {

    @Test
    @DisplayName("전체 공개 범위는 ALL scope key와 index 경로를 가진다")
    void createsAllScope() {
        WikiScope scope = WikiScope.all();

        assertThat(scope.scopeKey()).isEqualTo("ALL");
        assertThat(scope.visibilityType()).isEqualTo(WikiScopeVisibilityType.ALL);
        assertThat(scope.departmentRefs()).isEmpty();
        assertThat(scope.indexPath()).isEqualTo("ALL/index.md");
    }

    @Test
    @DisplayName("부서 공개 범위는 정렬된 부서 ID로 scope key와 index 경로를 가진다")
    void createsDepartmentScope() {
        WikiScope scope = WikiScope.department(List.of(2L, 1L, 2L));

        assertThat(scope.scopeKey()).isEqualTo("D1-D2");
        assertThat(scope.visibilityType()).isEqualTo(WikiScopeVisibilityType.DEPARTMENT);
        assertThat(scope.departmentRefs()).containsExactly(1L, 2L);
        assertThat(scope.indexPath()).isEqualTo("D1-D2/index.md");
    }
}
