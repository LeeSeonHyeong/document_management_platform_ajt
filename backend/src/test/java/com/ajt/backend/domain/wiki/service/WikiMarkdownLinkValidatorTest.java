package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Wiki Markdown 페이지 링크 검증")
class WikiMarkdownLinkValidatorTest {

    private final WikiMarkdownLinkValidator validator = new WikiMarkdownLinkValidator();

    @Test
    @DisplayName("같은 공간의 허용된 Wiki 페이지 링크와 외부 링크를 허용한다")
    void allowsKnownWikiPageAndExternalLinks() {
        Set<String> allowedAddresses = Set.of(
                "pages/leave-policy-a3f2.md",
                "pages/security-b7c1.md"
        );

        assertThatCode(() -> validator.validate(
                "[보안 규정](pages/security-b7c1.md)\n[외부](https://example.com)\n[목차](#목차)",
                allowedAddresses
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("없는 Wiki 페이지 링크는 거부한다")
    void rejectsUnknownWikiPageLink() {
        assertThatThrownBy(() -> validator.validate("[없는 문서](pages/missing.md)", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wiki 페이지 링크");
    }

    @Test
    @DisplayName("상위 경로를 포함한 Wiki 페이지 링크는 거부한다")
    void rejectsPathTraversalWikiPageLink() {
        assertThatThrownBy(() -> validator.validate("[탈출](pages/../secret.md)", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wiki 페이지 링크");
    }
}
