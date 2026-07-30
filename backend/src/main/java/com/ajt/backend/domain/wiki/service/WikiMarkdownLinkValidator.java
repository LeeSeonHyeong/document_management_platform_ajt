package com.ajt.backend.domain.wiki.service;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Markdown 본문의 같은 Wiki 공간 페이지 링크를 검증합니다. */
@Component
public class WikiMarkdownLinkValidator {

    private static final Pattern MARKDOWN_LINK =
            Pattern.compile("(?<!!)\\[[^]]*]\\(([^)\\s]+)(?:\\s+[^)]*)?\\)");

    public void validate(String markdown, Set<String> allowedAddresses) {
        Matcher matcher = MARKDOWN_LINK.matcher(markdown);
        while (matcher.find()) {
            String target = matcher.group(1);
            if (!target.startsWith("pages/")) {
                continue;
            }
            if (!target.endsWith(".md") || target.contains("..") || !allowedAddresses.contains(target)) {
                throw new IllegalArgumentException("Wiki 페이지 링크를 찾을 수 없습니다: " + target);
            }
        }
    }
}
