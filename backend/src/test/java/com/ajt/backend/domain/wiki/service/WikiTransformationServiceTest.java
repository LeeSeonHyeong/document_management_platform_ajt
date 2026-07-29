package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.WikiDocumentChangeType;
import com.ajt.backend.global.ai.client.WikiTransformationRequest;
import com.ajt.backend.global.ai.client.WikiTransformationResponse;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("FastAPI Wiki 변환 호출")
class WikiTransformationServiceTest {

    private static final String SCOPE_KEY = "ALL";
    private static final String CURRENT_INDEX =
            "# 목차\n\n- [휴가 규정](pages/101.md) — 연차와 반차 사용 기준\n- [근태 관리](pages/108.md)";

    private final AiClient aiClient = mock(AiClient.class);
    private final WikiRepository wikiRepository = mock(WikiRepository.class);
    private final WikiCategoryRepository wikiCategoryRepository = mock(WikiCategoryRepository.class);
    private final WikiFileStorage wikiFileStorage = mock(WikiFileStorage.class);
    private final WikiTransformationApplier applier = mock(WikiTransformationApplier.class);
    private final WikiTransformationService service = new WikiTransformationService(
            aiClient,
            wikiRepository,
            wikiCategoryRepository,
            wikiFileStorage,
            applier
    );

    @Test
    @DisplayName("선택된 Wiki를 같은 공간에서 재검증해 본문과 요약까지 채워 보낸다")
    void buildsRequestFromVerifiedWikis() throws Exception {
        Wiki selected = wiki(101L, 10L, "휴가 규정", List.of(15L, 18L), List.of(108L));
        given(wikiFileStorage.readIndex(SCOPE_KEY)).willReturn(CURRENT_INDEX);
        given(wikiRepository.findAllByScopeKeyAndIdIn(SCOPE_KEY, List.of(101L, 108L)))
                .willReturn(List.of(selected));
        given(wikiFileStorage.readWikiMarkdown("wiki/ALL/pages/101.md")).willReturn("# 휴가 규정\n본문");
        given(wikiCategoryRepository.findAllByScopeKeyOrderByNameAsc(SCOPE_KEY))
                .willReturn(List.of(category(10L, "인사·복무")));
        given(aiClient.transformWiki(any(WikiTransformationRequest.class))).willReturn(emptyResponse());
        given(applier.apply(any(), anyLong(), any())).willReturn(List.of(101L));

        WikiTransformationService.WikiTransformationResult result = service.transformForAddedDocument(
                42L,
                15L,
                SCOPE_KEY,
                "# 취업 규칙",
                List.of(101L, 108L)
        );

        assertThat(result.affectedWikiIds()).containsExactly(101L);
        assertThat(result.summary()).isEqualTo("요약");
        ArgumentCaptor<WikiTransformationRequest> captor =
                ArgumentCaptor.forClass(WikiTransformationRequest.class);
        org.mockito.Mockito.verify(aiClient).transformWiki(captor.capture());
        WikiTransformationRequest request = captor.getValue();
        assertThat(request.jobId()).isEqualTo("42");
        assertThat(request.documentId()).isEqualTo("15");
        assertThat(request.scopeKey()).isEqualTo(SCOPE_KEY);
        assertThat(request.changeType()).isEqualTo(WikiDocumentChangeType.DOCUMENT_ADDED);
        assertThat(request.parsedMarkdown()).isEqualTo("# 취업 규칙");
        assertThat(request.removedParsedMarkdown()).isNull();
        assertThat(request.currentIndex()).isEqualTo(CURRENT_INDEX);
        assertThat(request.currentCategories())
                .extracting(
                        WikiTransformationRequest.CurrentCategory::categoryId,
                        WikiTransformationRequest.CurrentCategory::name
                )
                .containsExactly(org.assertj.core.groups.Tuple.tuple("10", "인사·복무"));
        assertThat(request.selectedWikis()).singleElement().satisfies(wiki -> {
            assertThat(wiki.wikiId()).isEqualTo("101");
            assertThat(wiki.categoryId()).isEqualTo("10");
            assertThat(wiki.title()).isEqualTo("휴가 규정");
            assertThat(wiki.summary()).isEqualTo("연차와 반차 사용 기준");
            assertThat(wiki.contentMarkdown()).isEqualTo("# 휴가 규정\n본문");
            assertThat(wiki.documentRefs()).containsExactly("15", "18");
            assertThat(wiki.wikiRefs()).containsExactly("108");
        });
    }

    @Test
    @DisplayName("목차에 요약이 없으면 제목으로 대체한다")
    void fallsBackToTitleWhenSummaryMissing() throws Exception {
        Wiki selected = wiki(108L, 10L, "근태 관리", List.of(), List.of());
        given(wikiFileStorage.readIndex(SCOPE_KEY)).willReturn(CURRENT_INDEX);
        given(wikiRepository.findAllByScopeKeyAndIdIn(SCOPE_KEY, List.of(108L))).willReturn(List.of(selected));
        given(wikiFileStorage.readWikiMarkdown("wiki/ALL/pages/108.md")).willReturn("# 근태 관리");
        given(wikiCategoryRepository.findAllByScopeKeyOrderByNameAsc(SCOPE_KEY)).willReturn(List.of());
        given(aiClient.transformWiki(any(WikiTransformationRequest.class))).willReturn(emptyResponse());
        given(applier.apply(any(), anyLong(), any())).willReturn(List.of());

        service.transformForAddedDocument(42L, 15L, SCOPE_KEY, "# 취업 규칙", List.of(108L));

        ArgumentCaptor<WikiTransformationRequest> captor =
                ArgumentCaptor.forClass(WikiTransformationRequest.class);
        org.mockito.Mockito.verify(aiClient).transformWiki(captor.capture());
        assertThat(captor.getValue().selectedWikis()).singleElement()
                .satisfies(wiki -> assertThat(wiki.summary()).isEqualTo("근태 관리"));
    }

    @Test
    @DisplayName("본문 파일이 비어 있는 Wiki는 문맥에서 제외한다")
    void skipsWikiWithEmptyContent() throws Exception {
        Wiki empty = wiki(101L, 10L, "휴가 규정", List.of(), List.of());
        given(wikiFileStorage.readIndex(SCOPE_KEY)).willReturn(CURRENT_INDEX);
        given(wikiRepository.findAllByScopeKeyAndIdIn(SCOPE_KEY, List.of(101L))).willReturn(List.of(empty));
        given(wikiFileStorage.readWikiMarkdown("wiki/ALL/pages/101.md")).willReturn("");
        given(wikiCategoryRepository.findAllByScopeKeyOrderByNameAsc(SCOPE_KEY)).willReturn(List.of());
        given(aiClient.transformWiki(any(WikiTransformationRequest.class))).willReturn(emptyResponse());
        given(applier.apply(any(), anyLong(), any())).willReturn(List.of());

        service.transformForAddedDocument(42L, 15L, SCOPE_KEY, "# 취업 규칙", List.of(101L));

        ArgumentCaptor<WikiTransformationRequest> captor =
                ArgumentCaptor.forClass(WikiTransformationRequest.class);
        org.mockito.Mockito.verify(aiClient).transformWiki(captor.capture());
        assertThat(captor.getValue().selectedWikis()).isEmpty();
    }

    @Test
    @DisplayName("선택된 Wiki가 없으면 Wiki 조회 없이 빈 문맥으로 호출한다")
    void sendsEmptyContextWhenNothingSelected() throws Exception {
        given(wikiFileStorage.readIndex(SCOPE_KEY)).willReturn("# 목차");
        given(wikiCategoryRepository.findAllByScopeKeyOrderByNameAsc(SCOPE_KEY)).willReturn(List.of());
        given(aiClient.transformWiki(any(WikiTransformationRequest.class))).willReturn(emptyResponse());
        given(applier.apply(any(), anyLong(), any())).willReturn(List.of(201L));

        WikiTransformationService.WikiTransformationResult result =
                service.transformForAddedDocument(42L, 15L, SCOPE_KEY, "# 취업 규칙", List.of());

        assertThat(result.affectedWikiIds()).containsExactly(201L);
        org.mockito.Mockito.verify(wikiRepository, org.mockito.Mockito.never())
                .findAllByScopeKeyAndIdIn(any(), any());
    }

    private WikiTransformationResponse emptyResponse() {
        return new WikiTransformationResponse("요약", List.of(), List.of(), List.of(), List.of());
    }

    private Wiki wiki(long id, long categoryId, String title, List<Long> documentRefs, List<Long> wikiRefs)
            throws ReflectiveOperationException {
        Wiki wiki = Wiki.create(SCOPE_KEY, categoryId, title);
        assignId(wiki, id);
        wiki.assignStoragePath();
        wiki.addDocumentRefs(documentRefs);
        wikiRefs.forEach(wiki::addWikiRef);
        return wiki;
    }

    private WikiCategory category(long id, String name) throws ReflectiveOperationException {
        WikiCategory category = WikiCategory.create(SCOPE_KEY, name, null);
        assignId(category, id);
        return category;
    }

    private void assignId(Object target, long id) throws ReflectiveOperationException {
        Field idField = target.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(target, id);
    }
}
