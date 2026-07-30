package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiSearchChunk;
import com.ajt.backend.domain.wiki.repository.WikiSearchChunkRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Wiki Markdown을 헤더 단위 청크로 나누어 전문 검색 색인을 갱신합니다. */
@Component
public class WikiSearchIndexer {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    private final WikiSearchChunkRepository chunkRepository;

    public WikiSearchIndexer(WikiSearchChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    public void replace(Wiki wiki, String markdown) {
        String contentHash = sha256(markdown);
        List<WikiSearchChunk> chunks = chunkMarkdown(wiki, markdown);
        chunkRepository.deleteByWikiId(wiki.id());
        if (!chunks.isEmpty()) {
            chunkRepository.saveAll(chunks);
        }
        wiki.changeContentHash(contentHash);
        wiki.markSearchIndexed();
    }

    public void deleteByWikiId(long wikiId) {
        chunkRepository.deleteByWikiId(wikiId);
    }

    private List<WikiSearchChunk> chunkMarkdown(Wiki wiki, String markdown) {
        List<WikiSearchChunk> chunks = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        long chunkIndex = 0;

        for (String line : markdown.split("\\R", -1)) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                chunkIndex = appendChunk(chunks, wiki, chunkIndex, headings, current);
                int level = heading.group(1).length();
                while (headings.size() >= level) {
                    headings.removeLast();
                }
                headings.add(heading.group(2).strip());
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        appendChunk(chunks, wiki, chunkIndex, headings, current);
        return chunks;
    }

    private long appendChunk(
            List<WikiSearchChunk> chunks,
            Wiki wiki,
            long chunkIndex,
            List<String> headings,
            StringBuilder current
    ) {
        String content = current.toString().strip();
        if (!content.isEmpty()) {
            String breadcrumb = headings.isEmpty() ? null : String.join(" > ", headings);
            chunks.add(WikiSearchChunk.create(
                    wiki.id(), wiki.scopeKey(), chunkIndex, breadcrumb, content, sha256(content)
            ));
            chunkIndex++;
        }
        current.setLength(0);
        return chunkIndex;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 찾을 수 없습니다.", exception);
        }
    }
}
