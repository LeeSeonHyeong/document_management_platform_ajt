package com.ajt.backend.domain.wiki.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wiki 공간 목차(index.md)의 읽기·쓰기 형식입니다.
 *
 * <p>목차는 FastAPI 변환 요청의 {@code currentIndex}로 그대로 전달되므로 사람이 읽을 수 있는 Markdown이어야 하고,
 * Wiki 요약을 되읽을 수 있도록 항목 형식을 고정합니다.
 *
 * <pre>
 * # 목차
 *
 * - [휴가 규정](pages/101.md) — 연차와 반차 사용 기준
 * </pre>
 */
public final class WikiIndex {

    private static final String HEADING = "# 목차";
    private static final String SUMMARY_SEPARATOR = " — ";
    private static final Pattern ENTRY_PATTERN = Pattern.compile(
            "^\\s*-\\s*\\[(?<title>[^\\]]+)]\\(pages/(?<wikiId>\\d+)\\.md\\)(?:\\s+—\\s+(?<summary>.*?))?\\s*$"
    );

    private final Map<Long, Entry> entriesByWikiId;

    private WikiIndex(Map<Long, Entry> entriesByWikiId) {
        this.entriesByWikiId = entriesByWikiId;
    }

    public static WikiIndex parse(String indexMarkdown) {
        Map<Long, Entry> entries = new LinkedHashMap<>();
        if (indexMarkdown == null) {
            return new WikiIndex(entries);
        }
        for (String line : indexMarkdown.split("\\R")) {
            Matcher matcher = ENTRY_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            long wikiId = Long.parseLong(matcher.group("wikiId"));
            String summary = matcher.group("summary");
            entries.put(wikiId, new Entry(wikiId, matcher.group("title").trim(), normalize(summary)));
        }
        return new WikiIndex(entries);
    }

    /**
     * 목차에 적힌 Wiki 요약입니다. 항목이 없거나 요약이 비어 있으면 {@code null}입니다.
     */
    public String summaryOf(long wikiId) {
        Entry entry = entriesByWikiId.get(wikiId);
        return entry == null ? null : entry.summary();
    }

    public List<Entry> entries() {
        return List.copyOf(entriesByWikiId.values());
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

    public record Entry(long wikiId, String title, String summary) {

        public Entry {
            title = normalize(title);
            summary = normalize(summary);
            if (title == null) {
                throw new IllegalArgumentException("목차 항목 제목은 비어 있을 수 없습니다.");
            }
        }

        String toMarkdown() {
            String link = "- [%s](pages/%d.md)".formatted(title, wikiId);
            return summary == null ? link : link + SUMMARY_SEPARATOR + summary;
        }
    }

    /**
     * 순서 값이 비어 있는 항목은 뒤로 보내고, 같은 순서면 원래 등장 순서를 유지합니다.
     */
    static List<Entry> sortedByOrder(List<OrderedEntry> orderedEntries) {
        List<OrderedEntry> sorted = new ArrayList<>(orderedEntries);
        sorted.sort((left, right) -> {
            int leftOrder = left.order() == null ? Integer.MAX_VALUE : left.order();
            int rightOrder = right.order() == null ? Integer.MAX_VALUE : right.order();
            return Integer.compare(leftOrder, rightOrder);
        });
        return sorted.stream().map(OrderedEntry::entry).toList();
    }

    record OrderedEntry(Integer order, Entry entry) {
    }
}
