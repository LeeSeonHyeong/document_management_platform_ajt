package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileMutation;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.ai.client.WikiEditResponse;
import com.ajt.backend.global.ai.client.WikiTransformationResponse;
import com.ajt.backend.global.ai.client.WikiTransformationResponse.CategoryChange;
import com.ajt.backend.global.ai.client.WikiTransformationResponse.Evidence;
import com.ajt.backend.global.ai.client.WikiTransformationResponse.IndexEntry;
import com.ajt.backend.global.ai.client.WikiTransformationResponse.RelationChange;
import com.ajt.backend.global.ai.client.WikiTransformationResponse.WikiChange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * FastAPI Wiki 변환·수정 결과를 Spring Boot가 검증해 DB와 Wiki 파일에 반영합니다.
 *
 * <p>계약상 FastAPI는 구조화된 변경 결과만 돌려주고 실제 ID 발급과 링크·관계 검증은 Spring Boot가 합니다.
 * 새로 만들 Wiki·카테고리는 {@code tempWikiId}, {@code tempCategoryId}로 오고 이 클래스가 실제 ID로 바꿉니다.
 *
 * <p>반영 순서는 카테고리 → Wiki → 관계 → 목차입니다. 뒤 단계가 앞 단계에서 발급된 ID를 참조하기 때문입니다.
 */
@Component
public class WikiTransformationApplier {

    private static final String ACTION_CREATE = "create";
    private static final String ACTION_UPDATE = "update";
    private static final String ACTION_DELETE = "delete";
    private static final String ACTION_ADD = "add";
    private static final String ACTION_REMOVE = "remove";

    private final WikiRepository wikiRepository;
    private final WikiCategoryRepository wikiCategoryRepository;
    private final WikiFileStorage wikiFileStorage;
    private final WikiSearchIndexer wikiSearchIndexer;
    private final WikiMarkdownLinkValidator wikiMarkdownLinkValidator;
    private final WikiScopeRepository wikiScopeRepository;

    public WikiTransformationApplier(
            WikiRepository wikiRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiFileStorage wikiFileStorage,
            WikiSearchIndexer wikiSearchIndexer,
            WikiMarkdownLinkValidator wikiMarkdownLinkValidator,
            WikiScopeRepository wikiScopeRepository
    ) {
        this.wikiRepository = wikiRepository;
        this.wikiCategoryRepository = wikiCategoryRepository;
        this.wikiFileStorage = wikiFileStorage;
        this.wikiSearchIndexer = wikiSearchIndexer;
        this.wikiMarkdownLinkValidator = wikiMarkdownLinkValidator;
        this.wikiScopeRepository = wikiScopeRepository;
    }

    /**
     * 변환 결과를 반영하고 생성·수정된 Wiki ID를 반환합니다. 반환값은 문서의 document_wiki_refs가 됩니다.
     */
    public List<Long> apply(String scopeKey, long documentId, WikiTransformationResponse response) {
        return apply(
                scopeKey,
                documentId,
                response.categoryChanges(),
                response.wikiChanges(),
                response.relationChanges(),
                response.indexEntries()
        );
    }

    /**
     * 원본문서가 이 범위에서 빠진 결과를 반영합니다. (FR-DOC-008 범위 변경, DR-014 삭제)
     *
     * <p>문서 참조를 자동으로 더하지 않는다 — 빠지는 문서를 근거로 다시 적으면 안 되기 때문이다.
     * 반영이 끝난 뒤 이 범위에 남은 Wiki의 {@code documentRefs}에서 빠진 문서를 지운다. AI가
     * 응답의 근거 배열에 그 문서를 실어 보내도 이 단계에서 정리된다.
     *
     * <p>반영 <b>전에</b> 이 문서를 근거로 삼던 Wiki 수를 세어 함께 돌려준다(S15P11B106-225).
     * 호출자가 「걷어낼 것이 있었는데 AI가 아무것도 안 했다」를 판정하는 근거다. 반영 뒤에 세면
     * 참조가 이미 지워졌거나 Wiki 자체가 삭제돼 그 구분을 할 수 없다.
     */
    public RemovedDocumentResult applyRemovedDocument(
            String scopeKey,
            long documentId,
            WikiTransformationResponse response
    ) {
        long referencingWikiCount = wikiRepository.findAllByScopeKey(scopeKey).stream()
                .filter(wiki -> wiki.documentRefs().contains(documentId))
                .count();
        List<Long> affectedWikiIds = apply(
                scopeKey,
                null,
                response.categoryChanges(),
                response.wikiChanges(),
                response.relationChanges(),
                response.indexEntries()
        );
        for (Wiki wiki : wikiRepository.findAllByScopeKey(scopeKey)) {
            if (wiki.documentRefs().contains(documentId)) {
                wiki.removeDocumentRef(documentId);
            }
        }
        return new RemovedDocumentResult(affectedWikiIds, (int) referencingWikiCount);
    }

    /**
     * 걷어내기 반영 결과입니다.
     *
     * @param affectedWikiIds      반영으로 바뀐 Wiki. 삭제된 Wiki는 여기 들어가지 않는다
     * @param referencingWikiCount 반영 <b>전에</b> 이 문서를 근거로 삼던 Wiki 수
     */
    public record RemovedDocumentResult(List<Long> affectedWikiIds, int referencingWikiCount) {

        public RemovedDocumentResult {
            affectedWikiIds = List.copyOf(affectedWikiIds);
        }
    }

    /**
     * 관리자 대화로 지시한 Wiki 수정 결과를 반영합니다.
     *
     * <p>변경 목록 구조는 변환 응답과 같아 같은 반영 로직을 씁니다.
     * 다만 새 원본문서가 없으므로 문서 참조를 자동으로 더하지 않고, AI가 준 근거 문서만 반영합니다.
     */
    public List<Long> apply(String scopeKey, WikiEditResponse response) {
        return apply(
                scopeKey,
                null,
                response.categoryChanges(),
                response.wikiChanges(),
                response.relationChanges(),
                response.indexEntries()
        );
    }

    private List<Long> apply(
            String scopeKey,
            Long originDocumentId,
            List<CategoryChange> categoryChanges,
            List<WikiChange> wikiChanges,
            List<RelationChange> relationChanges,
            List<IndexEntry> indexEntries
    ) {
        WikiFileMutation fileMutation = beginFileMutation();
        try {
            WikiIndex previousIndex = WikiIndex.parse(readIndex(scopeKey));
            Map<String, Long> categoryIdsByRef = applyCategoryChanges(scopeKey, categoryChanges);
            Set<String> allowedWikiAddresses = allowedWikiAddresses(scopeKey, nullSafe(wikiChanges));
            WikiChangeResult wikiResult = applyWikiChanges(
                    scopeKey,
                    originDocumentId,
                    nullSafe(wikiChanges),
                    categoryIdsByRef,
                    allowedWikiAddresses,
                    fileMutation
            );
            applyRelationChanges(
                    scopeKey,
                    nullSafe(relationChanges),
                    wikiResult.wikiIdsByRef(),
                    wikiResult.deletedWikiIds()
            );
            writeIndex(scopeKey, nullSafe(indexEntries), wikiResult, previousIndex, fileMutation);
            incrementScopeVersionIfChanged(scopeKey, categoryChanges, wikiChanges, relationChanges, indexEntries);
            completeFileMutationAfterTransaction(fileMutation);
            return List.copyOf(wikiResult.affectedWikiIds());
        } catch (RuntimeException exception) {
            rollbackFileMutation(fileMutation, exception);
            throw exception;
        }
    }

    private void incrementScopeVersionIfChanged(
            String scopeKey,
            List<CategoryChange> categoryChanges,
            List<WikiChange> wikiChanges,
            List<RelationChange> relationChanges,
            List<IndexEntry> indexEntries
    ) {
        if (nullSafe(categoryChanges).isEmpty()
                && nullSafe(wikiChanges).isEmpty()
                && nullSafe(relationChanges).isEmpty()
                && nullSafe(indexEntries).isEmpty()) {
            return;
        }
        wikiScopeRepository.findById(scopeKey)
                .orElseThrow(() -> new IllegalStateException("Wiki 공간을 찾을 수 없습니다: " + scopeKey))
                .incrementScopeVersion();
    }

    private Map<String, Long> applyCategoryChanges(String scopeKey, List<CategoryChange> changes) {
        Map<String, Long> categoryIdsByRef = new LinkedHashMap<>();
        for (CategoryChange change : nullSafe(changes)) {
            switch (action(change.action())) {
                case ACTION_CREATE -> {
                    WikiCategory created = wikiCategoryRepository.saveAndFlush(
                            WikiCategory.create(scopeKey, change.name(), null)
                    );
                    if (change.tempCategoryId() != null && !change.tempCategoryId().isBlank()) {
                        categoryIdsByRef.put(change.tempCategoryId(), created.id());
                    }
                }
                case ACTION_UPDATE -> findCategory(scopeKey, change.categoryId()).changeName(change.name());
                case ACTION_DELETE -> deleteCategory(scopeKey, change.categoryId());
                default -> throw new IllegalArgumentException(
                        "지원하지 않는 카테고리 변경 동작입니다: " + change.action()
                );
            }
        }
        return categoryIdsByRef;
    }

    private void deleteCategory(String scopeKey, String categoryId) {
        WikiCategory category = findCategory(scopeKey, categoryId);
        if (wikiRepository.existsByWikiCategoryId(category.id())) {
            throw new IllegalStateException("Wiki가 남아 있는 카테고리는 삭제할 수 없습니다: " + category.name());
        }
        wikiCategoryRepository.delete(category);
    }

    private WikiChangeResult applyWikiChanges(
            String scopeKey,
            Long originDocumentId,
            List<WikiChange> changes,
            Map<String, Long> categoryIdsByRef,
            Set<String> allowedWikiAddresses,
            WikiFileMutation fileMutation
    ) {
        Map<String, Long> wikiIdsByRef = new LinkedHashMap<>();
        Set<Long> affectedWikiIds = new LinkedHashSet<>();
        Set<Long> deletedWikiIds = new LinkedHashSet<>();

        for (WikiChange change : changes) {
            switch (action(change.action())) {
                case ACTION_CREATE -> {
                    long categoryId = resolveCategoryId(scopeKey, change.wikiCategoryRef(), categoryIdsByRef);
                    Wiki created = wikiRepository.saveAndFlush(Wiki.create(scopeKey, categoryId, change.title()));
                    created.assignStoragePath(requireCreateWikiPath(change));
                    created.addDocumentRefs(evidenceDocumentIds(originDocumentId, change.evidence()));
                    String contentMarkdown = requireContent(change);
                    validateWikiLinks(contentMarkdown, allowedWikiAddresses);
                    storeContent(fileMutation, created.wikiPath(), contentMarkdown);
                    wikiSearchIndexer.replace(created, contentMarkdown);
                    wikiRepository.save(created);
                    if (change.tempWikiId() != null && !change.tempWikiId().isBlank()) {
                        wikiIdsByRef.put(change.tempWikiId(), created.id());
                    }
                    affectedWikiIds.add(created.id());
                }
                case ACTION_UPDATE -> {
                    Wiki wiki = findWiki(scopeKey, change.wikiId());
                    if (isPresent(change.title())) {
                        wiki.changeTitle(change.title());
                    }
                    if (isPresent(change.wikiCategoryRef())) {
                        wiki.changeCategory(resolveCategoryId(scopeKey, change.wikiCategoryRef(), categoryIdsByRef));
                    }
                    if (isPresent(change.contentMarkdown())) {
                        validateWikiLinks(change.contentMarkdown(), allowedWikiAddresses);
                        storeContent(fileMutation, wiki.wikiPath(), change.contentMarkdown());
                        wikiSearchIndexer.replace(wiki, change.contentMarkdown());
                    }
                    wiki.addDocumentRefs(evidenceDocumentIds(originDocumentId, change.evidence()));
                    affectedWikiIds.add(wiki.id());
                }
                case ACTION_DELETE -> {
                    Wiki wiki = findWiki(scopeKey, change.wikiId());
                    deleteWikiMarkdown(fileMutation, wiki.wikiPath());
                    wikiSearchIndexer.deleteByWikiId(wiki.id());
                    wikiRepository.delete(wiki);
                    deletedWikiIds.add(wiki.id());
                    affectedWikiIds.remove(wiki.id());
                }
                default -> throw new IllegalArgumentException(
                        "지원하지 않는 Wiki 변경 동작입니다: " + change.action()
                );
            }
        }
        removeDanglingWikiRefs(scopeKey, deletedWikiIds);
        return new WikiChangeResult(wikiIdsByRef, affectedWikiIds, deletedWikiIds);
    }

    /**
     * 삭제된 Wiki를 가리키던 관계 참조를 남은 Wiki에서 지웁니다.
     * wiki_refs는 JSON 컬럼이라 FK가 없어 앱이 정리해야 합니다.
     */
    private void removeDanglingWikiRefs(String scopeKey, Set<Long> deletedWikiIds) {
        if (deletedWikiIds.isEmpty()) {
            return;
        }
        for (Wiki wiki : wikiRepository.findAllByScopeKey(scopeKey)) {
            deletedWikiIds.forEach(wiki::removeWikiRef);
        }
    }

    /**
     * Wiki 사이 관계 변경을 반영합니다.
     *
     * <p>같은 응답에서 삭제된 Wiki를 가리키는 관계는 건너뜁니다. AI는 지우는 페이지의 나가는 링크도
     * {@code remove} 관계로 함께 실어 보내는데, 그 시점에 원본 Wiki는 이미 사라져 찾을 수 없다.
     * 이것을 오류로 보면 문서 삭제 걷어내기가 통째로 실패해 문서를 영영 지울 수 없다
     * (「같은 Wiki 공간에서 찾을 수 없는 Wiki입니다」로 실패, S15P11B106-195 후속).
     * 삭제된 Wiki를 가리키던 참조는 {@link #removeDanglingWikiRefs}가 이미 정리했으므로 할 일도 없다.
     * 삭제되지 않은 Wiki를 가리키는 잘못된 참조는 여전히 오류로 남긴다 — 다른 공간 침범을 막는 검증이다.
     */
    private void applyRelationChanges(
            String scopeKey,
            List<RelationChange> changes,
            Map<String, Long> wikiIdsByRef,
            Set<Long> deletedWikiIds
    ) {
        for (RelationChange change : changes) {
            long sourceWikiId = resolveWikiRef(change.sourceWikiRef(), wikiIdsByRef);
            long targetWikiId = resolveWikiRef(change.targetWikiRef(), wikiIdsByRef);
            if (deletedWikiIds.contains(sourceWikiId) || deletedWikiIds.contains(targetWikiId)) {
                continue;
            }
            Wiki source = findWiki(scopeKey, sourceWikiId);
            requireWikiInScope(scopeKey, targetWikiId);
            switch (action(change.action())) {
                case ACTION_ADD -> source.addWikiRef(targetWikiId);
                case ACTION_REMOVE -> source.removeWikiRef(targetWikiId);
                default -> throw new IllegalArgumentException(
                        "지원하지 않는 Wiki 관계 동작입니다: " + change.action()
                );
            }
        }
    }

    private void writeIndex(
            String scopeKey,
            List<IndexEntry> indexEntries,
            WikiChangeResult wikiResult,
            WikiIndex previousIndex,
            WikiFileMutation fileMutation
    ) {
        List<WikiIndex.Entry> entries = indexEntries.isEmpty()
                ? survivingPreviousEntries(scopeKey, previousIndex, wikiResult.deletedWikiIds())
                : resolvedEntries(scopeKey, indexEntries, wikiResult.wikiIdsByRef());
        storeIndex(fileMutation, scopeKey, WikiIndex.render(entries));
    }

    /**
     * AI가 목차를 돌려주지 않은 경우입니다. 기존 목차를 유지하되 삭제된 Wiki 항목만 걷어냅니다.
     */
    private List<WikiIndex.Entry> survivingPreviousEntries(
            String scopeKey,
            WikiIndex previousIndex,
            Set<Long> deletedWikiIds
    ) {
        Set<Long> existingWikiIds = wikiIdsInScope(scopeKey);
        return previousIndex.entries()
                .stream()
                .filter(entry -> !deletedWikiIds.contains(entry.wikiId()))
                .filter(entry -> existingWikiIds.contains(entry.wikiId()))
                .toList();
    }

    private List<WikiIndex.Entry> resolvedEntries(
            String scopeKey,
            List<IndexEntry> indexEntries,
            Map<String, Long> wikiIdsByRef
    ) {
        Map<Long, Wiki> wikisById = new LinkedHashMap<>();
        for (Wiki wiki : wikiRepository.findAllByScopeKey(scopeKey)) {
            wikisById.put(wiki.id(), wiki);
        }
        List<WikiIndex.OrderedEntry> ordered = new ArrayList<>();
        for (IndexEntry indexEntry : indexEntries) {
            long wikiId = resolveWikiRef(indexEntry.wikiRef(), wikiIdsByRef);
            Wiki wiki = wikisById.get(wikiId);
            if (wiki == null) {
                // 같은 응답에서 삭제됐거나 다른 공간의 Wiki를 가리키는 항목은 목차에 싣지 않는다.
                continue;
            }
            wiki.changeSummary(indexEntry.summary());
            String title = isPresent(indexEntry.title()) ? indexEntry.title() : wiki.title();
            ordered.add(new WikiIndex.OrderedEntry(
                    indexEntry.order(),
                    new WikiIndex.Entry(wikiId, title, indexEntry.summary())
            ));
        }
        return WikiIndex.sortedByOrder(ordered);
    }

    private long resolveCategoryId(String scopeKey, String categoryRef, Map<String, Long> categoryIdsByRef) {
        if (!isPresent(categoryRef)) {
            throw new IllegalArgumentException("wikiCategoryRef는 필수입니다.");
        }
        Long mapped = categoryIdsByRef.get(categoryRef);
        if (mapped != null) {
            return mapped;
        }
        return findCategory(scopeKey, categoryRef).id();
    }

    private long resolveWikiRef(String wikiRef, Map<String, Long> wikiIdsByRef) {
        if (!isPresent(wikiRef)) {
            throw new IllegalArgumentException("Wiki 참조값이 비어 있습니다.");
        }
        Long mapped = wikiIdsByRef.get(wikiRef);
        if (mapped != null) {
            return mapped;
        }
        return parseId(wikiRef, "Wiki 참조값");
    }

    private Wiki findWiki(String scopeKey, String wikiId) {
        return findWiki(scopeKey, parseId(wikiId, "wikiId"));
    }

    private Wiki findWiki(String scopeKey, long wikiId) {
        return wikiRepository.findById(wikiId)
                .filter(wiki -> wiki.belongsToScope(scopeKey))
                .orElseThrow(() -> new IllegalArgumentException(
                        "같은 Wiki 공간에서 찾을 수 없는 Wiki입니다: " + wikiId
                ));
    }

    private void requireWikiInScope(String scopeKey, long wikiId) {
        findWiki(scopeKey, wikiId);
    }

    private WikiCategory findCategory(String scopeKey, String categoryId) {
        long id = parseId(categoryId, "categoryId");
        return wikiCategoryRepository.findById(id)
                .filter(category -> category.belongsToScope(scopeKey))
                .orElseThrow(() -> new IllegalArgumentException(
                        "같은 Wiki 공간에서 찾을 수 없는 카테고리입니다: " + categoryId
                ));
    }

    private Set<Long> wikiIdsInScope(String scopeKey) {
        return wikiRepository.findAllByScopeKey(scopeKey)
                .stream()
                .map(Wiki::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 변경에 딸린 근거 문서 ID입니다.
     * 문서 추가로 시작된 변환이면 그 문서를 항상 포함하고, 관리자 대화 수정이면 AI가 준 근거만 씁니다.
     */
    private List<Long> evidenceDocumentIds(Long originDocumentId, List<Evidence> evidence) {
        Set<Long> documentIds = new LinkedHashSet<>();
        if (originDocumentId != null) {
            documentIds.add(originDocumentId);
        }
        for (Evidence item : nullSafe(evidence)) {
            Optional.ofNullable(item.documentId())
                    .filter(value -> !value.isBlank())
                    .map(value -> parseId(value, "근거 문서 ID"))
                    .ifPresent(documentIds::add);
        }
        return List.copyOf(documentIds);
    }

    private String requireContent(WikiChange change) {
        if (!isPresent(change.contentMarkdown())) {
            throw new IllegalArgumentException("새로 만드는 Wiki의 본문이 비어 있습니다: " + change.title());
        }
        return change.contentMarkdown();
    }

    private String requireCreateWikiPath(WikiChange change) {
        if (!isPresent(change.wikiPath())) {
            throw new IllegalArgumentException("새로 만드는 Wiki의 wikiPath는 필수입니다: " + change.title());
        }
        return change.wikiPath();
    }

    private Set<String> allowedWikiAddresses(String scopeKey, List<WikiChange> changes) {
        Set<String> addresses = wikiRepository.findAllByScopeKey(scopeKey).stream()
                .map(Wiki::wikiPath)
                .map(path -> wikiAddress(scopeKey, path))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (WikiChange change : changes) {
            if (!ACTION_CREATE.equals(action(change.action()))) {
                continue;
            }
            String address = wikiAddress(scopeKey, requireCreateWikiPath(change));
            if (!addresses.add(address)) {
                throw new IllegalArgumentException("같은 Wiki 공간에 이미 존재하는 wikiPath입니다: " + address);
            }
        }
        return addresses;
    }

    private String wikiAddress(String scopeKey, String wikiPath) {
        String prefix = "wiki/" + scopeKey + "/";
        if (!wikiPath.startsWith(prefix)) {
            throw new IllegalArgumentException("wikiPath는 해당 scope의 Wiki 경로여야 합니다: " + wikiPath);
        }
        return wikiPath.substring(prefix.length());
    }

    private void validateWikiLinks(String contentMarkdown, Set<String> allowedWikiAddresses) {
        wikiMarkdownLinkValidator.validate(contentMarkdown, allowedWikiAddresses);
    }

    private WikiFileMutation beginFileMutation() {
        try {
            return wikiFileStorage.beginMutation();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void storeContent(WikiFileMutation fileMutation, String wikiPath, String contentMarkdown) {
        try {
            fileMutation.storeWikiMarkdown(wikiPath, contentMarkdown);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void deleteWikiMarkdown(WikiFileMutation fileMutation, String wikiPath) {
        try {
            fileMutation.deleteWikiMarkdown(wikiPath);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String readIndex(String scopeKey) {
        try {
            return wikiFileStorage.readIndex(scopeKey);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void storeIndex(WikiFileMutation fileMutation, String scopeKey, String indexMarkdown) {
        try {
            fileMutation.storeIndex(scopeKey, indexMarkdown);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void completeFileMutationAfterTransaction(WikiFileMutation fileMutation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fileMutation.discardBackup();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    fileMutation.discardBackup();
                    return;
                }
                rollbackFileMutation(fileMutation, null);
            }
        });
    }

    private void rollbackFileMutation(WikiFileMutation fileMutation, RuntimeException originalException) {
        try {
            fileMutation.rollback();
        } catch (IOException rollbackException) {
            if (originalException != null) {
                originalException.addSuppressed(rollbackException);
                return;
            }
            throw new UncheckedIOException(rollbackException);
        }
    }

    private static long parseId(String value, String fieldName) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException | NullPointerException exception) {
            throw new IllegalArgumentException(fieldName + " 값이 올바르지 않습니다: " + value);
        }
    }

    private static String action(String action) {
        if (action == null) {
            throw new IllegalArgumentException("변경 동작이 비어 있습니다.");
        }
        return action.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record WikiChangeResult(
            Map<String, Long> wikiIdsByRef,
            Set<Long> affectedWikiIds,
            Set<Long> deletedWikiIds
    ) {
    }
}
