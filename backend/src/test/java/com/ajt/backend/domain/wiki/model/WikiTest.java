package com.ajt.backend.domain.wiki.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Wiki 엔티티")
class WikiTest {

    @Test
    @DisplayName("ID가 발급된 뒤에 본문 경로를 확정한다")
    void assignsStoragePathAfterIdIssued() throws Exception {
        Wiki wiki = Wiki.create("ALL", 10L, "휴가 규정");

        assertThatThrownBy(wiki::assignStoragePath)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wiki_id");

        assignId(wiki, 101L);
        assertThat(wiki.assignStoragePath()).isEqualTo("wiki/ALL/pages/101.md");
        assertThat(wiki.wikiPath()).isEqualTo("wiki/ALL/pages/101.md");
    }

    @Test
    @DisplayName("문서 참조는 중복 없이 더한다")
    void addsDocumentRefsWithoutDuplicates() {
        Wiki wiki = Wiki.create("ALL", 10L, "휴가 규정");

        wiki.addDocumentRefs(List.of(15L, 18L));
        wiki.addDocumentRefs(List.of(18L, 20L));

        assertThat(wiki.documentRefs()).containsExactly(15L, 18L, 20L);
    }

    @Test
    @DisplayName("관계 참조를 더하고 지운다")
    void addsAndRemovesWikiRefs() throws Exception {
        Wiki wiki = Wiki.create("ALL", 10L, "휴가 규정");
        assignId(wiki, 101L);

        wiki.addWikiRef(108L);
        wiki.addWikiRef(108L);
        wiki.addWikiRef(109L);
        assertThat(wiki.wikiRefs()).containsExactly(108L, 109L);

        wiki.removeWikiRef(108L);
        assertThat(wiki.wikiRefs()).containsExactly(109L);
    }

    @Test
    @DisplayName("자기 자신을 참조할 수 없다")
    void rejectsSelfReference() throws Exception {
        Wiki wiki = Wiki.create("ALL", 10L, "휴가 규정");
        assignId(wiki, 101L);

        assertThatThrownBy(() -> wiki.addWikiRef(101L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자기 자신");
    }

    @Test
    @DisplayName("제목은 비어 있을 수 없고 200자를 넘을 수 없다")
    void validatesTitle() {
        assertThatThrownBy(() -> Wiki.create("ALL", 10L, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Wiki.create("ALL", 10L, "가".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200자");
    }

    @Test
    @DisplayName("같은 Wiki 공간인지 확인한다")
    void checksScope() {
        Wiki wiki = Wiki.create("ALL", 10L, "휴가 규정");

        assertThat(wiki.belongsToScope("ALL")).isTrue();
        assertThat(wiki.belongsToScope("D1-D2")).isFalse();
    }

    private void assignId(Object target, long id) throws ReflectiveOperationException {
        Field idField = target.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(target, id);
    }
}
