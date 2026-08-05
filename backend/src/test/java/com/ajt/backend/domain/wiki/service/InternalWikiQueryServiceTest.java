package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiSearchChunk;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.repository.WikiSearchChunkRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;

class InternalWikiQueryServiceTest {

    private final WikiScopeRepository wikiScopeRepository = mock(WikiScopeRepository.class);
    private final WikiRepository wikiRepository = mock(WikiRepository.class);
    private final WikiCategoryRepository wikiCategoryRepository = mock(WikiCategoryRepository.class);
    private final WikiSearchChunkRepository wikiSearchChunkRepository = mock(WikiSearchChunkRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final WikiFileStorage wikiFileStorage = mock(WikiFileStorage.class);
    private final DocumentFileStorage documentFileStorage = mock(DocumentFileStorage.class);
    private final InternalWikiQueryService service = new InternalWikiQueryService(
            wikiScopeRepository, wikiRepository, wikiCategoryRepository, wikiSearchChunkRepository,
            documentRepository, wikiFileStorage, documentFileStorage);

    @Test
    void returnsWikiContentWithCurrentScopeVersion() throws Exception {
        WikiScope scope = mock(WikiScope.class);
        Wiki wiki = mock(Wiki.class);
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        given(wiki.scopeKey()).willReturn("D1-D2");
        given(wiki.id()).willReturn(101L);
        given(wiki.title()).willReturn("휴가 규정");
        given(wiki.wikiPath()).willReturn("wiki/D1-D2/pages/a3f2c1d4.md");
        given(wiki.contentHash()).willReturn("hash");
        given(wikiFileStorage.readWikiMarkdown(wiki.wikiPath())).willReturn("# 휴가 규정");

        InternalWikiQueryService.WikiContent response = service.content("D1-D2", 101L);

        assertThat(response.scopeVersion()).isEqualTo(47L);
        assertThat(response.wikiId()).isEqualTo("101");
        assertThat(response.contentMarkdown()).isEqualTo("# 휴가 규정");
    }

    @Test
    void hidesWikiOutsideRequestedScope() {
        WikiScope scope = mock(WikiScope.class);
        Wiki wiki = mock(Wiki.class);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        given(wiki.scopeKey()).willReturn("ALL");

        assertThatThrownBy(() -> service.content("D1-D2", 101L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_NOT_FOUND);
    }

    @Test
    void returnsSameScopeRelationsAndBacklinks() {
        WikiScope scope = mock(WikiScope.class);
        Wiki wiki = mock(Wiki.class);
        Wiki backlink = mock(Wiki.class);
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        given(wiki.scopeKey()).willReturn("D1-D2");
        given(wiki.id()).willReturn(101L);
        given(wiki.wikiRefs()).willReturn(List.of(102L));
        given(wiki.documentRefs()).willReturn(List.of(15L));
        given(backlink.id()).willReturn(108L);
        given(backlink.wikiRefs()).willReturn(List.of(101L));
        given(wikiRepository.findAllByScopeKey("D1-D2")).willReturn(List.of(wiki, backlink));

        InternalWikiQueryService.WikiRelations response = service.relations("D1-D2", 101L);

        assertThat(response.scopeVersion()).isEqualTo(47L);
        assertThat(response.wikiRefs()).containsExactly("102");
        assertThat(response.documentRefs()).containsExactly("15");
        assertThat(response.backlinks()).containsExactly("108");
    }

    @Test
    void returnsCurrentScopeIndex() throws Exception {
        WikiScope scope = mock(WikiScope.class);
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiFileStorage.readIndex("D1-D2")).willReturn("# 목차");

        InternalWikiQueryService.WikiIndex response = service.index("D1-D2");

        assertThat(response.scopeVersion()).isEqualTo(47L);
        assertThat(response.scopeKey()).isEqualTo("D1-D2");
        assertThat(response.indexMarkdown()).isEqualTo("# 목차");
    }

    @Test
    void returnsCategoriesWithWikiCounts() {
        WikiScope scope = mock(WikiScope.class);
        WikiCategory category = mock(WikiCategory.class);
        Wiki first = mock(Wiki.class);
        Wiki second = mock(Wiki.class);
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiCategoryRepository.findAllByScopeKeyOrderByNameAsc("D1-D2")).willReturn(List.of(category));
        given(category.id()).willReturn(9L);
        given(category.name()).willReturn("휴가");
        given(first.wikiCategoryId()).willReturn(9L);
        given(second.wikiCategoryId()).willReturn(9L);
        given(wikiRepository.findAllByScopeKey("D1-D2")).willReturn(List.of(first, second));

        InternalWikiQueryService.WikiCategories response = service.categories("D1-D2");

        assertThat(response.scopeVersion()).isEqualTo(47L);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.wikiCategoryId()).isEqualTo("9");
            assertThat(item.wikiCount()).isEqualTo(2L);
        });
    }

    @Test
    void returnsParsedDocumentWithinScope() throws Exception {
        WikiScope scope = mock(WikiScope.class);
        Document document = mock(Document.class);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(document.scopeKey()).willReturn("D1-D2");
        given(document.id()).willReturn(15L);
        given(document.originalFileName()).willReturn("취업규칙.pdf");
        given(document.parsedPath()).willReturn("wiki/D1-D2/sources/15/parsed.md");
        given(documentFileStorage.readText(document.parsedPath())).willReturn("# 취업 규칙");

        InternalWikiQueryService.ParsedDocument response = service.parsedDocument("D1-D2", 15L);

        assertThat(response.documentId()).isEqualTo("15");
        assertThat(response.parsedMarkdown()).isEqualTo("# 취업 규칙");
    }

    @Test
    void hidesMissingParsedDocumentWithContractErrorCode() {
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(mock(WikiScope.class)));
        given(documentRepository.findById(15L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.parsedDocument("D1-D2", 15L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    void returnsWikiPagesInStableIdOrder() {
        WikiScope scope = mock(WikiScope.class);
        Wiki high = mock(Wiki.class);
        Wiki low = mock(Wiki.class);
        WikiCategory category = mock(WikiCategory.class);
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(high.id()).willReturn(102L);
        given(low.id()).willReturn(101L);
        given(high.wikiCategoryId()).willReturn(9L);
        given(low.wikiCategoryId()).willReturn(9L);
        given(low.title()).willReturn("낮은 ID");
        given(low.summary()).willReturn("요약");
        given(low.wikiPath()).willReturn("wiki/D1-D2/pages/low.md");
        given(low.contentHash()).willReturn("hash");
        given(wikiRepository.findAllByScopeKey("D1-D2")).willReturn(List.of(high, low));
        given(wikiCategoryRepository.findById(9L)).willReturn(Optional.of(category));
        given(category.name()).willReturn("휴가");

        InternalWikiQueryService.WikiPages response = service.pages("D1-D2", 1, null);

        assertThat(response.scopeVersion()).isEqualTo(47L);
        assertThat(response.items()).singleElement().extracting(InternalWikiQueryService.WikiPage::wikiId)
                .isEqualTo("101");
        assertThat(response.nextCursor()).isEqualTo("101");
    }

    @Test
    void rejectsNonNumericWikiPageCursorAsBadRequest() {
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(mock(WikiScope.class)));

        assertThatThrownBy(() -> service.pages("D1-D2", 200, "not-a-number"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void searchesOnlyTheRequestedScopeWithBooleanTerms() {
        WikiScope scope = mock(WikiScope.class);
        Wiki wiki = mock(Wiki.class);
        WikiSearchChunk chunk = WikiSearchChunk.create(
                101L, "D1-D2", 0L, "휴가 > 연차", "연차는 15일입니다.", "hash");
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiSearchChunkRepository.searchByScopeKey("D1-D2", "연차 규정", 10))
                .willReturn(List.of(chunk));
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        given(wiki.title()).willReturn("휴가 규정");

        InternalWikiQueryService.WikiSearch response = service.search("D1-D2", "연차 규정", 10);

        then(wikiSearchChunkRepository).should().searchByScopeKey("D1-D2", "연차 규정", 10);
        assertThat(response.scopeVersion()).isEqualTo(47L);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.wikiId()).isEqualTo("101");
            assertThat(item.title()).isEqualTo("휴가 규정");
            assertThat(item.breadcrumb()).isEqualTo("휴가 > 연차");
            assertThat(item.snippet()).isEqualTo("연차는 15일입니다.");
        });
    }

    @Test
    void doesNotWrapAMultiWordQueryIntoAnExactPhrase() {
        // 앞 판본은 질의를 통째로 큰따옴표로 감쌌다. boolean mode 에서 그것은 정확 구문이고,
        // 색인이 ngram(2글자)이라 어절이 둘 이상인 질의는 그 연속 문자열이 본문에 그대로
        // 없으면 0건이 됐다 — 「출장비(여비) 정산 안내」가 있는데 `출장비 정산`이 0건이었다.
        // 실측(2026-08-05, corpus-ko dev): 1어절 0/10 0건, 2어절 이상 10/10 0건.
        WikiScope scope = mock(WikiScope.class);
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("ALL")).willReturn(Optional.of(scope));

        service.search("ALL", "출장비 정산 안내", 10);

        then(wikiSearchChunkRepository).should()
                .searchByScopeKey("ALL", "출장비 정산 안내", 10);
    }

    @Test
    void stripsBooleanOperatorsSoTheyAreNotReadAsOperators() {
        // 큰따옴표를 그냥 지우면 안 되는 이유. 그 따옴표가 의도치 않게 연산자를 무해화하고
        // 있었다 — `-` 를 그대로 넘기면 NOT 으로 읽혀 결과가 조용히 뒤집힌다.
        WikiScope scope = mock(WikiScope.class);
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("ALL")).willReturn(Optional.of(scope));

        service.search("ALL", "연차 -이월 +부여 (규정) \"인용\"", 10);

        then(wikiSearchChunkRepository).should()
                .searchByScopeKey("ALL", "연차  이월  부여  규정   인용", 10);
    }

    @Test
    void returnsEveryWikiEdgeInScopeWithCurrentScopeVersion() {
        WikiScope scope = mock(WikiScope.class);
        Wiki first = mock(Wiki.class);
        Wiki second = mock(Wiki.class);
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiRepository.findAllByScopeKey("D1-D2")).willReturn(List.of(first, second));
        given(first.id()).willReturn(101L);
        given(first.wikiRefs()).willReturn(List.of(102L, 115L));
        given(first.documentRefs()).willReturn(List.of(15L));
        given(second.id()).willReturn(102L);
        given(second.wikiRefs()).willReturn(List.of());
        given(second.documentRefs()).willReturn(List.of(15L, 16L));

        InternalWikiQueryService.WikiSpaceRelations response = service.spaceRelations("D1-D2");

        assertThat(response.scopeVersion()).isEqualTo(47L);
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).wikiId()).isEqualTo("101");
        assertThat(response.items().get(0).wikiRefs()).containsExactly("102", "115");
        assertThat(response.items().get(0).documentRefs()).containsExactly("15");
        assertThat(response.items().get(1).wikiId()).isEqualTo("102");
        assertThat(response.items().get(1).wikiRefs()).isEmpty();
        assertThat(response.items().get(1).documentRefs()).containsExactly("15", "16");
    }

    @Test
    void returnsEmptyItemsForScopeWithoutWiki() {
        WikiScope scope = mock(WikiScope.class);
        given(scope.scopeVersion()).willReturn(3L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiRepository.findAllByScopeKey("D1-D2")).willReturn(List.of());

        InternalWikiQueryService.WikiSpaceRelations response = service.spaceRelations("D1-D2");

        assertThat(response.scopeVersion()).isEqualTo(3L);
        assertThat(response.items()).isEmpty();
    }

    @Test
    void hidesSpaceRelationsForUnknownScope() {
        given(wikiScopeRepository.findById("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.spaceRelations("NOPE"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_SCOPE_NOT_FOUND);
    }

    @Test
    void keepsDanglingWikiRefsInsteadOfFilteringThem() {
        // 설계 4절 — 끊어진 참조를 걸러내면 데이터가 이미 깨져 있다는 사실이 숨는다.
        // 999 는 이 범위에 없는 Wiki 다. 그래도 응답에 그대로 실려야 한다.
        WikiScope scope = mock(WikiScope.class);
        Wiki only = mock(Wiki.class);
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiRepository.findAllByScopeKey("D1-D2")).willReturn(List.of(only));
        given(only.id()).willReturn(101L);
        given(only.wikiRefs()).willReturn(List.of(999L));
        given(only.documentRefs()).willReturn(List.of());

        InternalWikiQueryService.WikiSpaceRelations response = service.spaceRelations("D1-D2");

        assertThat(response.items().get(0).wikiRefs()).containsExactly("999");
    }
}
