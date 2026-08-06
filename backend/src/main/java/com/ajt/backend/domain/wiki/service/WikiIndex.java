package com.ajt.backend.domain.wiki.service;

import java.util.List;

/**
 * Wiki 공간 목차(index.md)의 <b>쓰기</b> 형식입니다.
 *
 * <p>목차는 FastAPI 변환 요청의 {@code currentIndex}로 그대로 전달되므로 사람이 읽을 수 있는
 * Markdown이어야 합니다.
 *
 * <p><b>되읽지 않습니다.</b> 예전에는 이 파일을 파싱해 Wiki 요약을 복원했는데, 요약은
 * {@code wiki.summary} 컬럼이 정본이 되어 그 경로가 사라졌습니다. 되읽는 코드가 없으므로
 * 형식을 잘못 가정할 곳도 없습니다.
 */
public final class WikiIndex {

    private static final String HEADING = "# 목차";
    private static final String SUMMARY_SEPARATOR = " — ";

    private WikiIndex() {
    }

    /**
     * 줄바꿈은 플랫폼과 무관하게 LF로 고정합니다. 목차 파일이 FastAPI 요청 본문으로 그대로 전달되기 때문입니다.
     */
    public static String render(List<Entry> entries) {
        StringBuilder builder = new StringBuilder(HEADING).append('\n');
        for (Entry entry : entries) {
            builder.append('\n').append(entry.toMarkdown());
        }
        return builder.toString();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * @param linkTarget 목차가 링크할 주소. <b>{@code wiki.wiki_path} 에서 유도한다.</b>
     *                   {@code wikiId} 로 만들면 하이드레이션 주소와 갈려 dangling-link 가 된다
     */
    public record Entry(String linkTarget, String title, String summary) {

        public Entry {
            linkTarget = normalize(linkTarget);
            title = normalize(title);
            summary = normalize(summary);
            if (linkTarget == null) {
                throw new IllegalArgumentException("목차 항목 링크 주소는 비어 있을 수 없습니다.");
            }
            if (title == null) {
                throw new IllegalArgumentException("목차 항목 제목은 비어 있을 수 없습니다.");
            }
        }

        String toMarkdown() {
            String link = "- [%s](%s)".formatted(title, linkTarget);
            return summary == null ? link : link + SUMMARY_SEPARATOR + summary;
        }
    }
}
