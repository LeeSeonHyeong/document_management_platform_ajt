package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.domain.wiki.storage.WikiFileMutation;
import com.ajt.backend.global.ai.client.WikiTransformationResponse;
import com.ajt.backend.global.ai.client.WikiTransformationResponse.CategoryChange;
import com.ajt.backend.global.ai.client.WikiTransformationResponse.Evidence;
import com.ajt.backend.global.ai.client.WikiTransformationResponse.IndexEntry;
import com.ajt.backend.global.ai.client.WikiTransformationResponse.RelationChange;
import com.ajt.backend.global.ai.client.WikiTransformationResponse.WikiChange;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("FastAPI Wiki 변환 결과 반영")
class WikiTransformationApplierTest {

    @Test
    @DisplayName("여러 신규 카테고리와 AI 발급 Wiki 경로를 각각 반영한다")
    void appliesEachWikiCategoryReferenceAndAgentIssuedPath() {
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(
                        new CategoryChange("create", null, "category-temp-1", "인사"),
                        new CategoryChange("create", null, "category-temp-2", "보안")
                ),
                List.of(
                        new WikiChange(
                                "create", null, "wiki-temp-1", "category-temp-1",
                                "wiki/ALL/pages/leave-policy.md", "휴가 규정", "# 휴가 규정", List.of()
                        ),
                        new WikiChange(
                                "create", null, "wiki-temp-2", "category-temp-2",
                                "wiki/ALL/pages/security-policy.md", "보안 규정", "# 보안 규정", List.of()
                        )
                ),
                List.of(),
                List.of()
        );

        applier.apply(SCOPE_KEY, DOCUMENT_ID, response);

        assertThat(savedWikis).extracting(Wiki::wikiCategoryId).containsExactly(10L, 11L);
        assertThat(savedWikis).extracting(Wiki::wikiPath).containsExactly(
                "wiki/ALL/pages/leave-policy.md",
                "wiki/ALL/pages/security-policy.md"
        );
    }

    private static final String SCOPE_KEY = "ALL";
    private static final long DOCUMENT_ID = 15L;

    private final WikiRepository wikiRepository = mock(WikiRepository.class);
    private final WikiCategoryRepository wikiCategoryRepository = mock(WikiCategoryRepository.class);
    private final WikiScopeRepository wikiScopeRepository = mock(WikiScopeRepository.class);
    private final WikiFileStorage wikiFileStorage = mock(WikiFileStorage.class);
    private final WikiFileMutation wikiFileMutation = mock(WikiFileMutation.class);
    private final WikiSearchIndexer wikiSearchIndexer = mock(WikiSearchIndexer.class);
    private final WikiTransformationApplier applier =
            new WikiTransformationApplier(
                    wikiRepository,
                    wikiCategoryRepository,
                    wikiFileStorage,
                    wikiSearchIndexer,
                    new WikiMarkdownLinkValidator(),
                    wikiScopeRepository
            );

    private final List<Wiki> savedWikis = new ArrayList<>();
    private final List<WikiCategory> savedCategories = new ArrayList<>();
    private final AtomicLong nextWikiId = new AtomicLong(101L);
    private final AtomicLong nextCategoryId = new AtomicLong(10L);

    @BeforeEach
    void setUpRepositories() throws Exception {
        given(wikiScopeRepository.findById(SCOPE_KEY)).willReturn(Optional.of(WikiScope.all()));
        given(wikiFileStorage.readIndex(SCOPE_KEY)).willReturn("# 목차");
        given(wikiFileStorage.beginMutation()).willReturn(wikiFileMutation);
        given(wikiRepository.saveAndFlush(any(Wiki.class))).willAnswer(invocation -> {
            Wiki wiki = invocation.getArgument(0);
            assignId(wiki, nextWikiId.getAndIncrement());
            savedWikis.add(wiki);
            return wiki;
        });
        given(wikiCategoryRepository.saveAndFlush(any(WikiCategory.class))).willAnswer(invocation -> {
            WikiCategory category = invocation.getArgument(0);
            assignId(category, nextCategoryId.getAndIncrement());
            savedCategories.add(category);
            return category;
        });
        given(wikiRepository.findAllByScopeKey(SCOPE_KEY)).willAnswer(invocation -> savedWikis.stream()
                .filter(wiki -> wiki.belongsToScope(SCOPE_KEY))
                .toList());
        given(wikiRepository.findById(anyLong())).willAnswer(invocation -> {
            long id = invocation.getArgument(0);
            return savedWikis.stream().filter(wiki -> id == wiki.id()).findFirst();
        });
        given(wikiCategoryRepository.findById(anyLong())).willAnswer(invocation -> {
            long id = invocation.getArgument(0);
            return savedCategories.stream().filter(category -> id == category.id()).findFirst();
        });
        given(wikiCategoryRepository.findAllByScopeKeyOrderByNameAsc(SCOPE_KEY))
                .willAnswer(invocation -> List.copyOf(savedCategories));
        willAnswer(invocation -> {
            savedWikis.remove(invocation.<Wiki>getArgument(0));
            return null;
        }).given(wikiRepository).delete(any(Wiki.class));
    }

    @Test
    @DisplayName("반영으로 Wiki 공간이 바뀌면 scopeVersion을 증가시킨다")
    void incrementsScopeVersionAfterApplyingChanges() {
        WikiScope scope = WikiScope.all();
        given(wikiScopeRepository.findById(SCOPE_KEY)).willReturn(Optional.of(scope));

        applier.apply(SCOPE_KEY, DOCUMENT_ID, new WikiTransformationResponse(
                "요약", List.of(), List.of(), List.of(), List.of(new IndexEntry("101", 1, "휴가", "요약"))));

        assertThat(scope.scopeVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("계약 성공 예시처럼 카테고리와 Wiki를 새로 만들고 목차를 다시 쓴다")
    void appliesContractExample() throws Exception {
        WikiTransformationResponse response = new WikiTransformationResponse(
                "휴가 규정 Wiki를 생성했습니다.",
                List.of(new CategoryChange("create", null, "category-temp-1", "휴가 및 근태")),
                List.of(new WikiChange(
                        "create",
                        null,
                        "wiki-temp-1",
                        "category-temp-1",
                        "wiki/ALL/pages/leave-policy.md",
                        "휴가 규정",
                        "# 휴가 규정\n연차는 15일",
                        List.of(new Evidence("15", "1", "3장 휴가", "연차는 15일을 부여한다"))
                )),
                List.of(),
                List.of(new IndexEntry("wiki-temp-1", 1, "휴가 규정", "연차와 반차 사용 기준"))
        );

        List<Long> affectedWikiIds = applier.apply(SCOPE_KEY, DOCUMENT_ID, response);

        assertThat(affectedWikiIds).containsExactly(101L);
        assertThat(savedCategories).singleElement()
                .satisfies(category -> assertThat(category.name()).isEqualTo("휴가 및 근태"));
        Wiki created = savedWikis.get(0);
        assertThat(created.title()).isEqualTo("휴가 규정");
        assertThat(created.summary()).isEqualTo("연차와 반차 사용 기준");
        assertThat(created.wikiCategoryId()).isEqualTo(10L);
        assertThat(created.wikiPath()).isEqualTo("wiki/ALL/pages/leave-policy.md");
        assertThat(created.documentRefs()).containsExactly(15L);
        then(wikiFileMutation).should().storeWikiMarkdown(
                "wiki/ALL/pages/leave-policy.md", "# 휴가 규정\n연차는 15일"
        );
        then(wikiFileMutation).should().storeIndex(
                SCOPE_KEY,
                "# 목차\n\n- [휴가 규정](pages/leave-policy.md) — 연차와 반차 사용 기준"
        );
    }

    @Test
    @DisplayName("근거 문서를 Wiki의 문서 참조에 더한다")
    void mergesEvidenceDocumentRefs() {
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(new CategoryChange("create", null, "category-temp-1", "인사")),
                List.of(new WikiChange(
                        "create",
                        null,
                        "wiki-temp-1",
                        "category-temp-1",
                        "wiki/ALL/pages/employment-rules.md",
                        "취업 규칙",
                        "# 취업 규칙",
                        List.of(new Evidence("18", "1", "1장", "인용"), new Evidence("15", "2", "2장", "인용"))
                )),
                List.of(),
                List.of()
        );

        applier.apply(SCOPE_KEY, DOCUMENT_ID, response);

        assertThat(savedWikis.get(0).documentRefs()).containsExactly(15L, 18L);
    }

    @Test
    @DisplayName("기존 Wiki 수정과 관계 추가를 반영한다")
    void appliesUpdateAndRelation() throws Exception {
        Wiki existing = existingWiki(101L, 10L, "휴가 규정");
        Wiki related = existingWiki(108L, 10L, "근태 관리");
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange(
                        "update",
                        "101",
                        null,
                        null,
                        "휴가 규정 개정",
                        "# 휴가 규정 개정",
                        List.of()
                )),
                List.of(new RelationChange("add", "101", "108")),
                List.of(
                        new IndexEntry("101", 1, null, "개정된 휴가 기준"),
                        new IndexEntry("108", 2, "근태 관리", null)
                )
        );

        List<Long> affectedWikiIds = applier.apply(SCOPE_KEY, DOCUMENT_ID, response);

        assertThat(affectedWikiIds).containsExactly(101L);
        assertThat(existing.title()).isEqualTo("휴가 규정 개정");
        assertThat(existing.wikiRefs()).containsExactly(108L);
        assertThat(existing.documentRefs()).containsExactly(15L);
        assertThat(related.wikiRefs()).containsExactly(101L);
        then(wikiFileMutation).should().storeWikiMarkdown(
                existing.wikiPath(), "# 휴가 규정 개정"
        );
        // 목차는 이제 AI 가 준 순서가 아니라 그 공간의 살아 있는 Wiki 전체를
        // 카테고리명 → 제목 순으로 다시 그린다. 두 Wiki 모두 카테고리가 같아(10L) 제목만으로
        // 정렬되고, "근태 관리"가 "휴가 규정 개정"보다 사전순으로 앞선다.
        then(wikiFileMutation).should().storeIndex(
                SCOPE_KEY,
                "# 목차\n\n- [근태 관리](pages/108.md)\n- [휴가 규정 개정](pages/101.md) — 개정된 휴가 기준"
        );
    }

    @Test
    @DisplayName("Wiki를 삭제하면 본문 파일과 다른 Wiki의 관계 참조까지 정리한다")
    void deletesWikiAndDanglingRefs() throws Exception {
        Wiki removed = existingWiki(101L, 10L, "폐지된 규정");
        Wiki survivor = existingWiki(108L, 10L, "근태 관리");
        survivor.addWikiRef(101L);
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange("delete", "101", null, null, null, null, List.of())),
                List.of(),
                List.of(new IndexEntry("108", 1, "근태 관리", null))
        );

        List<Long> affectedWikiIds = applier.apply(SCOPE_KEY, DOCUMENT_ID, response);

        assertThat(affectedWikiIds).isEmpty();
        assertThat(survivor.wikiRefs()).isEmpty();
        then(wikiFileMutation).should().deleteWikiMarkdown(removed.wikiPath());
        then(wikiRepository).should().delete(removed);
    }

    @Test
    @DisplayName("같은 응답에서 삭제된 Wiki를 가리키는 관계 변경은 건너뛴다")
    void skipsRelationChangesTouchingWikiDeletedInSameResponse() throws Exception {
        // AI는 지우는 페이지의 나가는 링크도 remove 관계로 함께 실어 보낸다. 이것을 오류로 보면
        // 문서 삭제 걷어내기가 통째로 실패해 문서를 지울 수 없었다.
        Wiki removed = existingWiki(101L, 10L, "폐지된 규정");
        Wiki survivor = existingWiki(108L, 10L, "근태 관리");
        removed.addWikiRef(108L);
        survivor.addWikiRef(101L);
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange("delete", "101", null, null, null, null, List.of())),
                List.of(
                        new RelationChange("remove", "101", "108"),
                        new RelationChange("remove", "108", "101")
                ),
                List.of(new IndexEntry("108", 1, "근태 관리", null))
        );

        List<Long> affectedWikiIds = applier.apply(SCOPE_KEY, DOCUMENT_ID, response);

        assertThat(affectedWikiIds).isEmpty();
        // 삭제된 Wiki를 가리키던 참조는 removeDanglingWikiRefs가 이미 정리한다.
        assertThat(survivor.wikiRefs()).isEmpty();
        then(wikiRepository).should().delete(removed);
    }

    @Test
    @DisplayName("삭제되지 않은 Wiki를 가리키는 잘못된 관계는 여전히 반영하지 않는다")
    void stillRejectsRelationToWikiOutsideScope() throws Exception {
        existingWiki(101L, 10L, "휴가 규정");
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(),
                List.of(new RelationChange("add", "101", "999")),
                List.of()
        );

        assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 Wiki 공간에서 찾을 수 없는 Wiki");
    }

    @Test
    @DisplayName("목차가 비어 오면 살아 있는 Wiki 로 다시 그리고 삭제된 Wiki 항목은 남기지 않는다")
    void redrawsIndexFromLiveWikisWhenNoIndexEntries() throws Exception {
        existingWiki(101L, 10L, "폐지된 규정");
        existingWiki(108L, 10L, "근태 관리");
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange("delete", "101", null, null, null, null, List.of())),
                List.of(),
                List.of()
        );

        applier.apply(SCOPE_KEY, DOCUMENT_ID, response);

        then(wikiFileMutation).should().storeIndex(
                SCOPE_KEY,
                "# 목차\n\n- [근태 관리](pages/108.md)"
        );
    }

    @Test
    @DisplayName("목차 파일 반영에 실패하면 앞서 바꾼 Wiki 파일을 보상한다")
    void rollsBackFilesWhenApplyFails() throws Exception {
        existingCategory(10L, "인사");
        given(wikiFileMutation.storeIndex(anyString(), anyString()))
                .willThrow(new java.io.IOException("index write failed"));
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange(
                        "create", null, "wiki-temp-1", "10",
                        "wiki/ALL/pages/leave.md", "휴가 규정", "# 휴가 규정", List.of()
                )),
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID, response))
                .isInstanceOf(java.io.UncheckedIOException.class);

        then(wikiFileMutation).should().rollback();
    }

    @Test
    @DisplayName("DB 트랜잭션이 롤백되면 파일 보상 작업을 실행한다")
    void rollsBackFilesWhenDatabaseTransactionRollsBack() throws Exception {
        existingCategory(10L, "인사");
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange(
                        "create", null, "wiki-temp-1", "10",
                        "wiki/ALL/pages/leave.md", "휴가 규정", "# 휴가 규정", List.of()
                )),
                List.of(),
                List.of()
        );
        TransactionSynchronizationManager.initSynchronization();
        try {
            applier.apply(SCOPE_KEY, DOCUMENT_ID, response);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            then(wikiFileMutation).should().rollback();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("카테고리를 특정할 수 없으면 임의로 고르지 않고 실패한다")
    void failsWhenCategoryIsAmbiguous() throws Exception {
        existingCategory(10L, "인사");
        existingCategory(11L, "총무");
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange(
                        "create", null, "wiki-temp-1", null,
                        "wiki/ALL/pages/leave-policy.md", "휴가 규정", "# 휴가 규정", List.of()
                )),
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wikiCategoryRef는 필수");
    }

    @Test
    @DisplayName("카테고리가 하나여도 wikiCategoryRef 없이는 Wiki를 만들지 않는다")
    void rejectsMissingCategoryReferenceEvenWhenOneCategoryExists() throws Exception {
        existingCategory(10L, "인사");
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange(
                        "create", null, "wiki-temp-1", null,
                        "wiki/ALL/pages/leave-policy.md", "휴가 규정", "# 휴가 규정", List.of()
                )),
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wikiCategoryRef는 필수");
    }

    @Test
    @DisplayName("새 Wiki의 wikiPath가 없으면 반영하지 않는다")
    void rejectsCreateWithoutWikiPath() throws Exception {
        existingCategory(10L, "인사");
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange("create", null, "wiki-temp-1", "10", "휴가 규정", "# 휴가 규정", List.of())),
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wikiPath");
    }

    @Test
    @DisplayName("같은 응답에서 만들지 않은 Wiki 페이지 링크는 반영하지 않는다")
    void rejectsUnknownWikiPageLink() throws Exception {
        existingCategory(10L, "인사");
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange(
                        "create", null, "wiki-temp-1", "10",
                        "wiki/ALL/pages/leave-policy.md", "휴가 규정",
                        "[없는 문서](pages/missing.md)", List.of()
                )),
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wiki 페이지 링크");
    }

    @Test
    @DisplayName("다른 공간의 Wiki를 가리키는 변경은 반영하지 않는다")
    void rejectsWikiFromAnotherScope() throws Exception {
        Wiki otherScopeWiki = Wiki.create("D1-D2", 10L, "다른 공간 Wiki");
        assignId(otherScopeWiki, 501L);
        savedWikis.add(otherScopeWiki);
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange("update", "501", null, null, "제목 변경", "# 본문", List.of())),
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 Wiki 공간에서 찾을 수 없는 Wiki");
    }

    @Test
    @DisplayName("모르는 변경 동작은 반영하지 않는다")
    void rejectsUnknownAction() throws Exception {
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange("merge", "101", null, null, "제목", "# 본문", List.of())),
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 Wiki 변경 동작");
        then(wikiFileStorage).should(never()).storeIndex(anyString(), anyString());
    }

    @Test
    @DisplayName("Wiki가 남아 있는 카테고리는 삭제하지 않는다")
    void rejectsCategoryDeleteWithWikis() throws Exception {
        existingCategory(10L, "인사");
        given(wikiRepository.existsByWikiCategoryId(10L)).willReturn(true);
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(new CategoryChange("delete", "10", null, "인사")),
                List.of(),
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID, response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Wiki가 남아 있는 카테고리");
    }

    @Test
    @DisplayName("새로 만드는 Wiki의 본문이 비어 있으면 실패한다")
    void rejectsBlankContentOnCreate() throws Exception {
        existingCategory(10L, "인사");
        WikiTransformationResponse response = new WikiTransformationResponse(
                "요약",
                List.of(),
                List.of(new WikiChange(
                        "create", null, "wiki-temp-1", "10",
                        "wiki/ALL/pages/leave-policy.md", "휴가 규정", " ", List.of()
                )),
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("본문이 비어 있습니다");
    }

    @Test
    @DisplayName("목차 링크가 wiki_path 의 파일 이름을 따른다")
    void indexLinksFollowTheStoredWikiPath() throws Exception {
        // 해시 경로(AI 가 만든 위키)와 숫자 경로(시드가 만든 위키)가 섞여 있어도
        // 둘 다 wiki_path 에서 유도되어야 한다.
        existingCategory(10L, "인사");
        Wiki hashed = existingWiki(101L, 10L, "휴가 규정");
        hashed.assignStoragePath("wiki/ALL/pages/a67336b0c716.md");

        applier.apply(SCOPE_KEY, DOCUMENT_ID, new WikiTransformationResponse(
                "요약", List.of(), List.of(), List.of(), List.of()));

        ArgumentCaptor<String> index = ArgumentCaptor.forClass(String.class);
        then(wikiFileMutation).should().storeIndex(eq(SCOPE_KEY), index.capture());
        assertThat(index.getValue())
                .contains("(pages/a67336b0c716.md)")
                .doesNotContain("(pages/101.md)");
    }

    @Test
    @DisplayName("해시 경로와 숫자 경로가 두 카테고리에 섞여도 목차 전체가 카테고리명→제목순으로 그려진다")
    void indexMixesHashAndNumericPathsSortedByCategoryThenTitle() throws Exception {
        // 실제 데이터 모양: AI 가 만든 위키는 해시 경로, 시드가 만든 위키는 숫자 경로다.
        // wikiId 로 되돌리면 해시 경로 두 항목의 링크만 pages/{wikiId}.md 로 바뀌고,
        // 이미 pages/{wikiId}.md 형태인 숫자 경로 항목은 바뀌지 않는다 — 그래서 해시 경로가
        // 최소 하나 있어야 회귀를 구분할 수 있다.
        existingCategory(10L, "인사");
        existingCategory(20L, "보안");

        Wiki leavePolicy = existingWiki(101L, 10L, "휴가 규정");
        leavePolicy.assignStoragePath("wiki/ALL/pages/a67336b0c716.md");
        leavePolicy.changeSummary("연차 기준");

        Wiki welfare = existingWiki(102L, 10L, "복지 제도");
        welfare.changeSummary("복지 안내");

        Wiki accessPolicy = existingWiki(201L, 20L, "출입 정책");
        accessPolicy.assignStoragePath("wiki/ALL/pages/b91f2c0a33dd.md");
        accessPolicy.changeSummary("출입증 발급");

        applier.apply(SCOPE_KEY, DOCUMENT_ID, new WikiTransformationResponse(
                "요약", List.of(), List.of(), List.of(), List.of()));

        ArgumentCaptor<String> index = ArgumentCaptor.forClass(String.class);
        then(wikiFileMutation).should().storeIndex(eq(SCOPE_KEY), index.capture());
        assertThat(index.getValue()).isEqualTo(
                "# 목차\n"
                        + "\n- [출입 정책](pages/b91f2c0a33dd.md) — 출입증 발급"
                        + "\n- [복지 제도](pages/102.md) — 복지 안내"
                        + "\n- [휴가 규정](pages/a67336b0c716.md) — 연차 기준");
    }

    @Test
    @DisplayName("관계 추가를 양쪽 Wiki 에 저장한다")
    void addsRelationToBothWikis() throws Exception {
        Wiki source = existingWiki(101L, 10L, "휴가 규정");
        Wiki target = existingWiki(108L, 10L, "근태 관리");

        applier.apply(SCOPE_KEY, DOCUMENT_ID, new WikiTransformationResponse(
                "요약", List.of(), List.of(),
                List.of(new RelationChange("add", "101", "108")),
                List.of()));

        assertThat(source.wikiRefs()).containsExactly(108L);
        assertThat(target.wikiRefs()).containsExactly(101L);
    }

    @Test
    @DisplayName("관계 삭제를 양쪽 Wiki 에서 지운다")
    void removesRelationFromBothWikis() throws Exception {
        Wiki source = existingWiki(101L, 10L, "휴가 규정");
        Wiki target = existingWiki(108L, 10L, "근태 관리");
        source.addWikiRef(108L);
        target.addWikiRef(101L);

        applier.apply(SCOPE_KEY, DOCUMENT_ID, new WikiTransformationResponse(
                "요약", List.of(), List.of(),
                List.of(new RelationChange("remove", "101", "108")),
                List.of()));

        assertThat(source.wikiRefs()).isEmpty();
        assertThat(target.wikiRefs()).isEmpty();
    }

    @Test
    @DisplayName("자기 자신을 가리키는 관계는 양쪽 저장에서도 거부한다")
    void rejectsSelfRelation() throws Exception {
        existingWiki(101L, 10L, "휴가 규정");

        assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID,
                new WikiTransformationResponse(
                        "요약", List.of(), List.of(),
                        List.of(new RelationChange("add", "101", "101")),
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자기 자신");
    }

    // ------------------------------------------------------------------
    // 결정적 프리패스 — 근거가 삭제 문서뿐인 위키는 LLM 판단 없이 지운다.
    //
    // 배경(2026-08-07 LangSmith 실측): 이 삭제를 에이전트에게 맡기면, 지울 페이지를
    // 다른 페이지가 링크할 때 지시 규칙(목록 밖 수정 금지 vs dangling-link 수정)이
    // 충돌해 같은 read 를 53연속 반복하다 호출 상한에서 죽었다.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("근거가 삭제 문서뿐인 위키를 지우고 남은 본문의 링크를 평문화한다")
    void prunesFullyDependentWikisAndFlattensInboundLinks() throws Exception {
        existingCategory(10L, "보안 정책");
        Wiki dependent = existingWiki(101L, 10L, "AJT 정보보안 기본 정책");
        dependent.addDocumentRefs(List.of(15L));
        Wiki survivor = existingWiki(102L, 10L, "신입사원 온보딩 가이드");
        survivor.addDocumentRefs(List.of(16L));
        survivor.addWikiRef(101L);
        given(wikiFileStorage.readWikiMarkdown(survivor.wikiPath())).willReturn(
                "온보딩 첫 주에 [AJT 정보보안 기본 정책](pages/101.md)을 읽는다.\n"
                        + "복지는 [복지 제도](pages/205.md) 참고.");

        WikiTransformationApplier.PruneResult result =
                applier.pruneFullyDependentWikis(SCOPE_KEY, 15L, "인사규정.pdf");

        // 위키 삭제 — 행·색인·파일이 모두 정리된다.
        assertThat(savedWikis).extracting(Wiki::id).containsExactly(102L);
        then(wikiSearchIndexer).should().deleteByWikiId(101L);
        then(wikiFileMutation).should().deleteWikiMarkdown("wiki/ALL/pages/101.md");
        // 남은 위키의 관계 참조가 정리된다.
        assertThat(survivor.wikiRefs()).isEmpty();
        // 본문 링크는 평문화된다 — 텍스트는 남고, 다른 페이지 링크는 그대로다.
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        then(wikiFileMutation).should().storeWikiMarkdown(eq(survivor.wikiPath()), body.capture());
        assertThat(body.getValue())
                .contains("온보딩 첫 주에 AJT 정보보안 기본 정책을 읽는다.")
                .contains("[복지 제도](pages/205.md)")
                .doesNotContain("pages/101.md");
        then(wikiSearchIndexer).should().replace(eq(survivor), anyString());
        assertThat(result.deletedWikis()).containsExactly(
                new WikiTransformationApplier.PrunedWiki(101L, "AJT 정보보안 기본 정책"));
        assertThat(result.flattenedLinkPages()).isEqualTo(1);
        assertThat(result.remainingReferencingWikis()).isZero();
    }

    @Test
    @DisplayName("근거가 남는 위키는 지우지 않고 남은 참조 수를 알린다")
    void keepsWikisWithOtherEvidenceAndReportsRemaining() throws Exception {
        existingCategory(10L, "인사");
        Wiki mixed = existingWiki(101L, 10L, "휴가 규정");
        mixed.addDocumentRefs(List.of(15L, 16L));
        // 본문이 실제로 인용해야 "남는 참조"다 (S15P11B106-312) — 인용 없는 참조는 정리된다.
        given(wikiFileStorage.readWikiMarkdown(mixed.wikiPath()))
                .willReturn("연차는 15일이다[^1].\n\n[^1]: 인사규정.pdf, 3장 — \"연차 15일\"");

        WikiTransformationApplier.PruneResult result =
                applier.pruneFullyDependentWikis(SCOPE_KEY, 15L, "인사규정.pdf");

        assertThat(savedWikis).extracting(Wiki::id).containsExactly(101L);
        assertThat(result.deletedWikiTitles()).isEmpty();
        assertThat(result.remainingReferencingWikis()).isEqualTo(1);
        // 지운 것이 없으면 파일·버전도 건드리지 않는다.
        then(wikiFileMutation).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("프리패스로 공간이 바뀌면 목차를 다시 그리고 scopeVersion을 올린다")
    void redrawsIndexAndBumpsVersionAfterPrune() throws Exception {
        WikiScope scope = WikiScope.all();
        given(wikiScopeRepository.findById(SCOPE_KEY)).willReturn(Optional.of(scope));
        existingCategory(10L, "보안 정책");
        Wiki dependent = existingWiki(101L, 10L, "AJT 정보보안 기본 정책");
        dependent.addDocumentRefs(List.of(15L));
        Wiki survivor = existingWiki(102L, 10L, "신입사원 온보딩 가이드");
        survivor.addDocumentRefs(List.of(16L));
        given(wikiFileStorage.readWikiMarkdown(survivor.wikiPath())).willReturn("링크 없는 본문");

        applier.pruneFullyDependentWikis(SCOPE_KEY, 15L, "인사규정.pdf");

        ArgumentCaptor<String> index = ArgumentCaptor.forClass(String.class);
        then(wikiFileMutation).should().storeIndex(eq(SCOPE_KEY), index.capture());
        assertThat(index.getValue()).contains("신입사원 온보딩 가이드").doesNotContain("정보보안 기본 정책");
        assertThat(scope.scopeVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("본문에 인용이 없는 근거 참조는 프리패스가 정리하고 남은 참조로 세지 않는다(S15P11B106-312)")
    void detachesStaleRefsWithoutBodyCitations() throws Exception {
        // 2026-08-07 실측(문서 44): refs 에는 있는데 본문 각주가 0건 → 에이전트가 옳게
        // "걷어낼 것 없음"을 내도 S15P11B106-225 가드가 오탐 실패를 냈다.
        existingCategory(10L, "인사");
        Wiki stale = existingWiki(101L, 10L, "보상 체계");
        stale.addDocumentRefs(List.of(15L, 16L));
        given(wikiFileStorage.readWikiMarkdown(stale.wikiPath()))
                .willReturn("본문은 다른 문서만 인용한다[^1].\n\n[^1]: 다른문서.pdf, 절 — \"내용\"");

        WikiTransformationApplier.PruneResult result =
                applier.pruneFullyDependentWikis(SCOPE_KEY, 15L, "인사규정.pdf");

        assertThat(stale.documentRefs()).containsExactly(16L);
        assertThat(result.remainingReferencingWikis()).isZero();
        assertThat(result.detachedStaleRefWikis()).isEqualTo(1);
        assertThat(result.deletedWikiTitles()).isEmpty();
    }

    @Test
    @DisplayName("본문이 인용하는 참조는 남은 참조로 남는다(S15P11B106-312)")
    void keepsRefsBackedByBodyCitations() throws Exception {
        existingCategory(10L, "인사");
        Wiki citing = existingWiki(101L, 10L, "휴가 규정");
        citing.addDocumentRefs(List.of(15L, 16L));
        given(wikiFileStorage.readWikiMarkdown(citing.wikiPath()))
                .willReturn("연차는 15일이다[^1].\n\n[^1]: 인사규정.pdf, 3장 — \"연차 15일\"");

        WikiTransformationApplier.PruneResult result =
                applier.pruneFullyDependentWikis(SCOPE_KEY, 15L, "인사규정.pdf");

        assertThat(citing.documentRefs()).containsExactly(15L, 16L);
        assertThat(result.remainingReferencingWikis()).isEqualTo(1);
        assertThat(result.detachedStaleRefWikis()).isZero();
    }

    @Test
    @DisplayName("NFD 파일명도 NFC 본문 인용과 같다고 판정한다(S15P11B106-312, 278 함정)")
    void normalizesFilenamesBeforeCitationCheck() throws Exception {
        existingCategory(10L, "인사");
        Wiki citing = existingWiki(101L, 10L, "휴가 규정");
        citing.addDocumentRefs(List.of(15L, 16L));
        // 본문(에이전트 작성)은 NFC.
        given(wikiFileStorage.readWikiMarkdown(citing.wikiPath()))
                .willReturn("연차[^1].\n\n[^1]: 인사규정.pdf, 3장 — \"연차\"");
        // macOS 업로드 파일명은 NFD 로 온다.
        String nfdFileName = java.text.Normalizer.normalize("인사규정.pdf", java.text.Normalizer.Form.NFD);

        WikiTransformationApplier.PruneResult result =
                applier.pruneFullyDependentWikis(SCOPE_KEY, 15L, nfdFileName);

        // NFC 정규화 없이는 인용을 못 찾아 stale 로 오판된다 — refs 가 남아야 한다.
        assertThat(citing.documentRefs()).containsExactly(15L, 16L);
        assertThat(result.remainingReferencingWikis()).isEqualTo(1);
    }

    @Test
    @DisplayName("파일명을 모르면 참조를 정리하지 않는다 — 보수적으로 인용 있음 취급(S15P11B106-312)")
    void keepsRefsWhenFilenameIsUnknown() throws Exception {
        existingCategory(10L, "인사");
        Wiki wiki = existingWiki(101L, 10L, "휴가 규정");
        wiki.addDocumentRefs(List.of(15L, 16L));

        WikiTransformationApplier.PruneResult result =
                applier.pruneFullyDependentWikis(SCOPE_KEY, 15L, null);

        assertThat(wiki.documentRefs()).containsExactly(15L, 16L);
        assertThat(result.remainingReferencingWikis()).isEqualTo(1);
    }

    private Wiki existingWiki(long id, long categoryId, String title) throws ReflectiveOperationException {
        Wiki wiki = Wiki.create(SCOPE_KEY, categoryId, title);
        assignId(wiki, id);
        wiki.assignStoragePath();
        savedWikis.add(wiki);
        return wiki;
    }

    private WikiCategory existingCategory(long id, String name) throws ReflectiveOperationException {
        WikiCategory category = WikiCategory.create(SCOPE_KEY, name, null);
        assignId(category, id);
        savedCategories.add(category);
        return category;
    }

    private void assignId(Object target, long id) throws ReflectiveOperationException {
        Field idField = target.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(target, id);
    }

    @Test
    @DisplayName("AI 가 언급하지 않은 Wiki 도 목차에 싣는다")
    void writesEveryLiveWikiIntoTheIndex() throws Exception {
        // 실측(ALL 공간): DB 에 9개인데 목차에는 7개만 있었다. AI 가 만들지 않은 위키(시드)는
        // 목차에 들어간 적이 없어, 그 위키의 요약이 화면에서 비어 보였다.
        existingCategory(10L, "인사");
        Wiki mentioned = existingWiki(101L, 10L, "휴가 규정");
        Wiki unmentioned = existingWiki(108L, 10L, "복지 제도");
        mentioned.changeSummary("연차와 반차 기준");
        unmentioned.changeSummary("사내 복지 안내");

        applier.apply(SCOPE_KEY, DOCUMENT_ID, new WikiTransformationResponse(
                "요약", List.of(), List.of(), List.of(),
                List.of(new IndexEntry("101", 1, null, "연차와 반차 기준"))));

        ArgumentCaptor<String> index = ArgumentCaptor.forClass(String.class);
        then(wikiFileMutation).should().storeIndex(eq(SCOPE_KEY), index.capture());
        assertThat(index.getValue()).contains("휴가 규정").contains("복지 제도");
    }

    @Test
    @DisplayName("AI 가 목차 항목을 주지 않아도 살아 있는 Wiki 로 다시 그린다")
    void redrawsIndexEvenWithoutIndexEntries() throws Exception {
        // 예전에는 옛 목차를 그대로 유지했다. 그 경로로 죽은 항목이 목차에 남았고,
        // 다음 작업에서 lint 가 dangling-link 로 실패시켜 그 공간이 영구히 막혔다.
        existingCategory(10L, "인사");
        Wiki alive = existingWiki(101L, 10L, "휴가 규정");
        alive.changeSummary("연차와 반차 기준");
        given(wikiFileStorage.readIndex(SCOPE_KEY))
                .willReturn("# 목차\n\n- [사라진 페이지](pages/999.md) — 옛 항목");

        applier.apply(SCOPE_KEY, DOCUMENT_ID, new WikiTransformationResponse(
                "요약", List.of(), List.of(), List.of(), List.of()));

        ArgumentCaptor<String> index = ArgumentCaptor.forClass(String.class);
        then(wikiFileMutation).should().storeIndex(eq(SCOPE_KEY), index.capture());
        assertThat(index.getValue()).contains("휴가 규정").doesNotContain("사라진 페이지");
    }
}
