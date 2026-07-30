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
    void searchesOnlyTheRequestedScopeWithBooleanPhrase() {
        WikiScope scope = mock(WikiScope.class);
        Wiki wiki = mock(Wiki.class);
        WikiSearchChunk chunk = WikiSearchChunk.create(
                101L, "D1-D2", 0L, "휴가 > 연차", "연차는 15일입니다.", "hash");
        given(scope.scopeVersion()).willReturn(47L);
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.of(scope));
        given(wikiSearchChunkRepository.searchByScopeKey("D1-D2", "\"연차 규정\"", 10))
                .willReturn(List.of(chunk));
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        given(wiki.title()).willReturn("휴가 규정");

        InternalWikiQueryService.WikiSearch response = service.search("D1-D2", "연차 규정", 10);

        then(wikiSearchChunkRepository).should().searchByScopeKey("D1-D2", "\"연차 규정\"", 10);
        assertThat(response.scopeVersion()).isEqualTo(47L);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.wikiId()).isEqualTo("101");
            assertThat(item.title()).isEqualTo("휴가 규정");
            assertThat(item.breadcrumb()).isEqualTo("휴가 > 연차");
            assertThat(item.snippet()).isEqualTo("연차는 15일입니다.");
        });
    }
}
