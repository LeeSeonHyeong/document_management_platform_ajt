package com.ajt.backend.domain.document.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("원본문서 카테고리")
class DocumentCategoryTest {

    @Test
    @DisplayName("카테고리는 공개 범위와 이름을 가진다")
    void createsCategoryInScope() {
        DocumentCategory category = DocumentCategory.create("D1-D2", "인사규정", "인사 관련 문서");

        assertThat(category.scopeKey()).isEqualTo("D1-D2");
        assertThat(category.name()).isEqualTo("인사규정");
        assertThat(category.description()).isEqualTo("인사 관련 문서");
    }

    @Test
    @DisplayName("카테고리의 공개 범위가 요청 scope와 같은지 확인한다")
    void checksScope() {
        DocumentCategory category = DocumentCategory.create("ALL", "공통규정", null);

        assertThat(category.belongsToScope("ALL")).isTrue();
        assertThat(category.belongsToScope("D1")).isFalse();
    }
}
