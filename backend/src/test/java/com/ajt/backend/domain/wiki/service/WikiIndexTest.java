package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Wiki 공간 목차 형식")
class WikiIndexTest {

    @Test
    @DisplayName("목차 항목에서 Wiki ID와 요약을 읽는다")
    void parsesEntries() {
        WikiIndex index = WikiIndex.parse("""
                # 목차

                - [휴가 규정](pages/101.md) — 연차와 반차 사용 기준
                - [취업 규칙](pages/102.md)
                """);

        assertThat(index.entries())
                .extracting(WikiIndex.Entry::wikiId, WikiIndex.Entry::title, WikiIndex.Entry::summary)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(101L, "휴가 규정", "연차와 반차 사용 기준"),
                        org.assertj.core.groups.Tuple.tuple(102L, "취업 규칙", null)
                );
        assertThat(index.summaryOf(101L)).isEqualTo("연차와 반차 사용 기준");
        assertThat(index.summaryOf(102L)).isNull();
        assertThat(index.summaryOf(999L)).isNull();
    }

    @Test
    @DisplayName("목차 형식이 아닌 줄은 무시한다")
    void ignoresNonEntryLines() {
        WikiIndex index = WikiIndex.parse("""
                # 목차

                본문 설명 문장
                - [잘못된 링크](other/101.md)
                - [휴가 규정](pages/101.md)
                """);

        assertThat(index.entries())
                .extracting(WikiIndex.Entry::wikiId)
                .containsExactly(101L);
    }

    @Test
    @DisplayName("빈 목차와 null을 안전하게 처리한다")
    void parsesEmptyIndex() {
        assertThat(WikiIndex.parse(null).entries()).isEmpty();
        assertThat(WikiIndex.parse("# 목차").entries()).isEmpty();
    }

    @Test
    @DisplayName("항목을 다시 쓰면 되읽을 수 있는 형식으로 만든다")
    void rendersReparsableIndex() {
        String rendered = WikiIndex.render(List.of(
                new WikiIndex.Entry(101L, "휴가 규정", "연차와 반차 사용 기준"),
                new WikiIndex.Entry(102L, "취업 규칙", null)
        ));

        assertThat(rendered).isEqualTo("""
                # 목차

                - [휴가 규정](pages/101.md) — 연차와 반차 사용 기준
                - [취업 규칙](pages/102.md)""");
        assertThat(WikiIndex.parse(rendered).summaryOf(101L)).isEqualTo("연차와 반차 사용 기준");
    }

    @Test
    @DisplayName("순서 값이 없는 항목은 뒤로 보내고 같은 순서면 원래 순서를 유지한다")
    void sortsByOrderWithNullsLast() {
        List<WikiIndex.Entry> sorted = WikiIndex.sortedByOrder(List.of(
                new WikiIndex.OrderedEntry(null, new WikiIndex.Entry(103L, "세 번째", null)),
                new WikiIndex.OrderedEntry(2, new WikiIndex.Entry(102L, "둘", null)),
                new WikiIndex.OrderedEntry(1, new WikiIndex.Entry(101L, "하나", null)),
                new WikiIndex.OrderedEntry(2, new WikiIndex.Entry(104L, "둘 다음", null))
        ));

        assertThat(sorted)
                .extracting(WikiIndex.Entry::wikiId)
                .containsExactly(101L, 102L, 104L, 103L);
    }

    @Test
    @DisplayName("제목 없는 목차 항목은 만들 수 없다")
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> new WikiIndex.Entry(101L, " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("제목");
    }
}
