package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Wiki 공간 목차 형식")
class WikiIndexTest {

    @Test
    @DisplayName("항목을 다시 쓰면 사람이 읽을 수 있는 Markdown이 된다")
    void rendersReparsableIndex() {
        String rendered = WikiIndex.render(List.of(
                new WikiIndex.Entry("pages/101.md", "휴가 규정", "연차와 반차 사용 기준"),
                new WikiIndex.Entry("pages/102.md", "취업 규칙", null)
        ));

        assertThat(rendered).isEqualTo("""
                # 목차

                - [휴가 규정](pages/101.md) — 연차와 반차 사용 기준
                - [취업 규칙](pages/102.md)""");
    }

    @Test
    @DisplayName("제목 없는 목차 항목은 만들 수 없다")
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> new WikiIndex.Entry("pages/101.md", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("제목");
    }

    @Test
    @DisplayName("목차 링크는 wiki_path 에서 유도한다 — wikiId 로 만들지 않는다")
    void linksAreDerivedFromWikiPath() {
        // 하이드레이션이 wiki_path 로 페이지 주소를 만들기 때문에, 목차도 같은 출처를 써야
        // 두 이름이 갈리지 않는다. wikiId 로 만들면 그 간극을 메우는 변환이 필요해지고,
        // 변환이 실패한 항목은 dangling-link 로 그 공간의 문서 처리를 막는다.
        String markdown = WikiIndex.render(List.of(
                new WikiIndex.Entry("pages/a67336b0c716.md", "정보보안 지침", "계정·비밀번호 관리"),
                new WikiIndex.Entry("pages/14.md", "[샘플] 취업규칙 위키", null)));

        assertThat(markdown).isEqualTo("""
                # 목차

                - [정보보안 지침](pages/a67336b0c716.md) — 계정·비밀번호 관리
                - [[샘플] 취업규칙 위키](pages/14.md)""");
    }
}
