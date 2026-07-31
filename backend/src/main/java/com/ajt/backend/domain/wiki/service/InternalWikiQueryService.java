package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiSearchChunk;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.repository.WikiSearchChunkRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FastAPI 전용 Wiki 조회 창구의 데이터 조립 계층입니다. */
@Service
public class InternalWikiQueryService {

    private final WikiScopeRepository wikiScopeRepository;
    private final WikiRepository wikiRepository;
    private final WikiCategoryRepository wikiCategoryRepository;
    private final WikiSearchChunkRepository wikiSearchChunkRepository;
    private final DocumentRepository documentRepository;
    private final WikiFileStorage wikiFileStorage;
    private final DocumentFileStorage documentFileStorage;

    public InternalWikiQueryService(
            WikiScopeRepository wikiScopeRepository,
            WikiRepository wikiRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiSearchChunkRepository wikiSearchChunkRepository,
            DocumentRepository documentRepository,
            WikiFileStorage wikiFileStorage,
            DocumentFileStorage documentFileStorage
    ) {
        this.wikiScopeRepository = wikiScopeRepository;
        this.wikiRepository = wikiRepository;
        this.wikiCategoryRepository = wikiCategoryRepository;
        this.wikiSearchChunkRepository = wikiSearchChunkRepository;
        this.documentRepository = documentRepository;
        this.wikiFileStorage = wikiFileStorage;
        this.documentFileStorage = documentFileStorage;
    }

    @Transactional(readOnly = true)
    public WikiContent content(String scopeKey, long wikiId) {
        WikiScope scope = requireScope(scopeKey);
        Wiki wiki = wikiRepository.findById(wikiId)
                .filter(candidate -> scopeKey.equals(candidate.scopeKey()))
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_NOT_FOUND));
        try {
            return new WikiContent(
                    scope.scopeVersion(),
                    String.valueOf(wiki.id()),
                    wiki.title(),
                    wiki.wikiPath(),
                    wikiFileStorage.readWikiMarkdown(wiki.wikiPath()),
                    wiki.contentHash());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.WIKI_NOT_FOUND);
        }
    }

    @Transactional(readOnly = true)
    public WikiRelations relations(String scopeKey, long wikiId) {
        WikiScope scope = requireScope(scopeKey);
        Wiki wiki = wikiRepository.findById(wikiId)
                .filter(candidate -> scopeKey.equals(candidate.scopeKey()))
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_NOT_FOUND));
        List<String> backlinks = wikiRepository.findAllByScopeKey(scopeKey).stream()
                .filter(candidate -> !candidate.id().equals(wiki.id()))
                .filter(candidate -> candidate.wikiRefs().contains(wiki.id()))
                .map(candidate -> String.valueOf(candidate.id()))
                .toList();
        return new WikiRelations(
                scope.scopeVersion(),
                String.valueOf(wiki.id()),
                wiki.wikiRefs().stream().map(String::valueOf).toList(),
                wiki.documentRefs().stream().map(String::valueOf).toList(),
                backlinks);
    }

    /**
     * 범위 전체의 참조 그래프를 한 번에 돌려줍니다.
     *
     * <p>에이전트가 병합·삭제로 남의 링크를 깨뜨리지 않으려면 범위 전체의 간선을 알아야
     * 합니다. Wiki 1건씩 조회하면 장수만큼 호출이 나가므로 FastAPI 는 이 API 를 1회
     * 호출합니다.
     *
     * <p>역링크는 싣지 않습니다. 전체 간선이 있으면 소비자가 뒤집어 구할 수 있고, 여기서
     * 계산하면 Wiki 마다 전체를 훑는 O(n²) 이 됩니다.
     *
     * <p>{@code wiki_refs} 를 원본 그대로 싣습니다. 존재하지 않는 Wiki 를 가리키는 ID 를
     * 걸러내지 않습니다 — 삭제 정리가 트랜잭션 안에서 돌아 그런 값이 생길 실제 경로가 없고,
     * 필터는 데이터가 이미 깨져 있다는 사실을 숨깁니다.
     */
    @Transactional(readOnly = true)
    public WikiSpaceRelations spaceRelations(String scopeKey) {
        WikiScope scope = requireScope(scopeKey);
        return new WikiSpaceRelations(
                scope.scopeVersion(),
                wikiRepository.findAllByScopeKey(scopeKey).stream()
                        .map(wiki -> new WikiRelationItem(
                                String.valueOf(wiki.id()),
                                wiki.wikiRefs().stream().map(String::valueOf).toList(),
                                wiki.documentRefs().stream().map(String::valueOf).toList()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public WikiIndex index(String scopeKey) {
        WikiScope scope = requireScope(scopeKey);
        try {
            return new WikiIndex(scope.scopeVersion(), scopeKey, wikiFileStorage.readIndex(scopeKey));
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.WIKI_SCOPE_NOT_FOUND);
        }
    }

    @Transactional(readOnly = true)
    public WikiCategories categories(String scopeKey) {
        WikiScope scope = requireScope(scopeKey);
        Map<Long, Long> counts = wikiRepository.findAllByScopeKey(scopeKey).stream()
                .collect(Collectors.groupingBy(Wiki::wikiCategoryId, Collectors.counting()));
        List<WikiCategoryItem> items = wikiCategoryRepository.findAllByScopeKeyOrderByNameAsc(scopeKey).stream()
                .map(category -> new WikiCategoryItem(
                        String.valueOf(category.id()), category.name(), counts.getOrDefault(category.id(), 0L)))
                .toList();
        return new WikiCategories(scope.scopeVersion(), items);
    }

    @Transactional(readOnly = true)
    public ParsedDocument parsedDocument(String scopeKey, long documentId) {
        requireScope(scopeKey);
        Document document = documentRepository.findById(documentId)
                .filter(candidate -> scopeKey.equals(candidate.scopeKey()))
                .filter(candidate -> candidate.parsedPath() != null && !candidate.parsedPath().isBlank())
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        try {
            return new ParsedDocument(
                    String.valueOf(document.id()), document.originalFileName(), documentFileStorage.readText(document.parsedPath()));
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
    }

    @Transactional(readOnly = true)
    public WikiPages pages(String scopeKey, int limit, String cursor) {
        WikiScope scope = requireScope(scopeKey);
        long cursorId = parseCursor(cursor);
        List<Wiki> selected = wikiRepository.findAllByScopeKey(scopeKey).stream()
                .filter(wiki -> wiki.id() > cursorId)
                .sorted(Comparator.comparing(Wiki::id))
                .limit((long) limit + 1)
                .toList();
        boolean hasNext = selected.size() > limit;
        List<Wiki> page = hasNext ? selected.subList(0, limit) : selected;
        List<WikiPage> items = page.stream().map(wiki -> new WikiPage(
                String.valueOf(wiki.id()), wiki.title(), wiki.summary(), String.valueOf(wiki.wikiCategoryId()),
                wikiCategoryRepository.findById(wiki.wikiCategoryId()).map(category -> category.name()).orElse(null),
                wiki.wikiPath(), wiki.contentHash(), wiki.updatedAt())).toList();
        String nextCursor = hasNext ? String.valueOf(page.getLast().id()) : null;
        return new WikiPages(scope.scopeVersion(), nextCursor, items);
    }

    @Transactional(readOnly = true)
    public WikiSearch search(String scopeKey, String query, int limit) {
        WikiScope scope = requireScope(scopeKey);
        List<WikiSearchItem> items = wikiSearchChunkRepository.searchByScopeKey(scopeKey, quotePhrase(query), limit).stream()
                .map(chunk -> new WikiSearchItem(
                        String.valueOf(chunk.wikiId()), titleOf(chunk.wikiId()), chunk.breadcrumb(), chunk.content(),
                        chunk.chunkIndex(), chunk.contentHash()))
                .toList();
        return new WikiSearch(scope.scopeVersion(), items);
    }

    private String titleOf(long wikiId) {
        return wikiRepository.findById(wikiId).map(Wiki::title).orElse("");
    }

    private String quotePhrase(String query) {
        return "\"" + query.replace("\"", "") + "\"";
    }

    private long parseCursor(String cursor) {
        if (cursor == null) {
            return 0L;
        }
        try {
            long cursorId = Long.parseLong(cursor);
            if (cursorId < 0) {
                throw new NumberFormatException("cursor must not be negative");
            }
            return cursorId;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private WikiScope requireScope(String scopeKey) {
        return wikiScopeRepository.findById(scopeKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_SCOPE_NOT_FOUND));
    }

    public record WikiContent(
            long scopeVersion,
            String wikiId,
            String title,
            String wikiPath,
            String contentMarkdown,
            String contentHash
    ) {
    }

    public record WikiRelations(
            long scopeVersion,
            String wikiId,
            List<String> wikiRefs,
            List<String> documentRefs,
            List<String> backlinks
    ) {
    }

    public record WikiSpaceRelations(long scopeVersion, List<WikiRelationItem> items) {
    }

    public record WikiRelationItem(String wikiId, List<String> wikiRefs,
                                   List<String> documentRefs) {
    }

    public record WikiIndex(long scopeVersion, String scopeKey, String indexMarkdown) {
    }

    public record WikiCategories(long scopeVersion, List<WikiCategoryItem> items) {
    }

    public record WikiCategoryItem(String wikiCategoryId, String name, long wikiCount) {
    }

    public record ParsedDocument(String documentId, String originalFileName, String parsedMarkdown) {
    }

    public record WikiPages(long scopeVersion, String nextCursor, List<WikiPage> items) {
    }

    public record WikiPage(String wikiId, String title, String summary, String wikiCategoryId,
                           String categoryName, String wikiPath, String contentHash, java.time.Instant updatedAt) {
    }

    public record WikiSearch(long scopeVersion, List<WikiSearchItem> items) {
    }

    public record WikiSearchItem(String wikiId, String title, String breadcrumb, String snippet,
                                 long chunkIndex, String contentHash) {
    }
}
