package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    private final WikiFileStorage wikiFileStorage = mock(WikiFileStorage.class);
    private final WikiFileMutation wikiFileMutation = mock(WikiFileMutation.class);
    private final WikiSearchIndexer wikiSearchIndexer = mock(WikiSearchIndexer.class);
    private final WikiTransformationApplier applier =
            new WikiTransformationApplier(
                    wikiRepository,
                    wikiCategoryRepository,
                    wikiFileStorage,
                    wikiSearchIndexer,
                    new WikiMarkdownLinkValidator()
            );

    private final List<Wiki> savedWikis = new ArrayList<>();
    private final List<WikiCategory> savedCategories = new ArrayList<>();
    private final AtomicLong nextWikiId = new AtomicLong(101L);
    private final AtomicLong nextCategoryId = new AtomicLong(10L);

    @BeforeEach
    void setUpRepositories() throws Exception {
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
                "# 목차\n\n- [휴가 규정](pages/101.md) — 연차와 반차 사용 기준"
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
        assertThat(related.wikiRefs()).isEmpty();
        then(wikiFileMutation).should().storeWikiMarkdown(
                existing.wikiPath(), "# 휴가 규정 개정"
        );
        then(wikiFileMutation).should().storeIndex(
                SCOPE_KEY,
                "# 목차\n\n- [휴가 규정 개정](pages/101.md) — 개정된 휴가 기준\n- [근태 관리](pages/108.md)"
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
    @DisplayName("목차가 비어 오면 기존 목차를 유지하고 삭제된 Wiki 항목만 걷어낸다")
    void keepsPreviousIndexWhenNoIndexEntries() throws Exception {
        existingWiki(101L, 10L, "폐지된 규정");
        existingWiki(108L, 10L, "근태 관리");
        given(wikiFileStorage.readIndex(SCOPE_KEY)).willReturn(
                "# 목차\n\n- [폐지된 규정](pages/101.md) — 옛 기준\n- [근태 관리](pages/108.md) — 출퇴근"
        );
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
                "# 목차\n\n- [근태 관리](pages/108.md) — 출퇴근"
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
}
