package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.model.WikiScopeVisibilityType;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.service.CurrentMember;
import com.ajt.backend.domain.document.service.CurrentMemberProvider;
import com.ajt.backend.domain.document.service.CurrentMemberRole;
import com.ajt.backend.domain.member.DepartmentScopePolicy;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.ScopeAccess;
import com.ajt.backend.domain.wiki.api.WikiListResponse;
import com.ajt.backend.domain.wiki.api.WikiSummaryResponse;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StreamUtils;

@DisplayName("Wiki 목록·검색 서비스")
class WikiQueryServiceTest {

    private final CurrentMemberProvider currentMemberProvider = mock(CurrentMemberProvider.class);
    private final WikiRepository wikiRepository = mock(WikiRepository.class);
    private final WikiCategoryRepository wikiCategoryRepository = mock(WikiCategoryRepository.class);
    private final WikiFileStorage wikiFileStorage = mock(WikiFileStorage.class);
    private final WikiScopeRepository wikiScopeRepository = mock(WikiScopeRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
    private final DepartmentScopePolicy departmentScopePolicy = superAdminScopePolicy();
    private final WikiQueryService service = new WikiQueryService(
            currentMemberProvider,
            wikiRepository,
            wikiCategoryRepository,
            wikiFileStorage,
            wikiScopeRepository,
            memberRepository,
            documentRepository,
            departmentRepository,
            departmentScopePolicy
    );

    // 기존 테스트의 관리자는 전체 접근(최고관리자)으로 취급해 기존 동작을 유지한다(S15P11B106-199).
    private static DepartmentScopePolicy superAdminScopePolicy() {
        DepartmentScopePolicy policy = mock(DepartmentScopePolicy.class);
        given(policy.resolve(anyLong())).willReturn(ScopeAccess.superAdmin());
        return policy;
    }

    @Test
    @DisplayName("관리자는 접근 제한 없이 목록을 조회하고, 요약은 컬럼에서 채운다")
    void adminListsWikis() throws Exception {
        Wiki wiki = wiki("ALL", 9L, "휴가 규정", 101L);
        wiki.changeSummary("연차와 반차 사용 기준");
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(wikiRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(wiki), PageRequest.of(0, 20), 1));
        given(wikiCategoryRepository.findAllById(any())).willReturn(List.of(wikiCategory(9L, "휴가 및 근태")));

        WikiListResponse response = service.findWikis(1, 20, null, null, null, null);

        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.page()).isEqualTo(1);
        WikiSummaryResponse item = response.items().get(0);
        assertThat(item.wikiId()).isEqualTo("101");
        assertThat(item.title()).isEqualTo("휴가 규정");
        assertThat(item.summary()).isEqualTo("연차와 반차 사용 기준");
        assertThat(item.wikiCategoryId()).isEqualTo("9");
        assertThat(item.wikiCategoryName()).isEqualTo("휴가 및 근태");
        assertThat(item.scopeKey()).isEqualTo("ALL");
        // 관리자는 접근범위 계산이 필요 없다.
        verify(memberRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("사원은 접근 가능한 공개범위의 Wiki만 조회하며, 소속 부서·공개범위를 조회한다")
    void employeeListsAccessibleWikis() throws Exception {
        Wiki wiki = wiki("D2", 9L, "부서 규정", 202L);
        wiki.changeSummary("부서 전용 규정");
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(20L, CurrentMemberRole.EMPLOYEE));
        Department department = mock(Department.class);
        given(department.getId()).willReturn(2L);
        Member member = mock(Member.class);
        given(member.getDepartment()).willReturn(department);
        given(memberRepository.findById(20L)).willReturn(java.util.Optional.of(member));
        WikiScope scope = mock(WikiScope.class);
        given(scope.departmentRefs()).willReturn(List.of(2L));
        given(scope.scopeKey()).willReturn("D2");
        given(wikiScopeRepository.findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT))
                .willReturn(List.of(scope));
        given(wikiRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(wiki), PageRequest.of(0, 20), 1));
        given(wikiCategoryRepository.findAllById(any())).willReturn(List.of(wikiCategory(9L, "부서 규정")));

        WikiListResponse response = service.findWikis(1, 20, null, null, null, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).wikiId()).isEqualTo("202");
        assertThat(response.items().get(0).summary()).isEqualTo("부서 전용 규정");
        verify(memberRepository).findById(20L);
        verify(wikiScopeRepository).findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT);
    }

    @Test
    @DisplayName("목차에 없는 Wiki 의 요약도 컬럼에서 읽어 채운다")
    void fillsSummaryFromColumnEvenWhenAbsentFromIndex() throws Exception {
        // 목차 파일에 이 위키 항목이 없다. 예전에는 목차에서 요약을 읽어 null 이 됐다.
        // 실데이터에서 확인된 상태다 — 시드로 만든 위키(14·15)는 목차에 들어간 적이 없어,
        // 컬럼에 요약이 있는데도 목록 화면에서 비어 보였다.
        Wiki wiki = wiki("ALL", 9L, "[샘플] 취업규칙 위키", 14L);
        wiki.changeSummary("근무·휴가·복무 규정 요약(시연용)");
        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(wikiRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(wiki), PageRequest.of(0, 20), 1));
        given(wikiCategoryRepository.findAllById(any()))
                .willReturn(List.of(wikiCategory(9L, "샘플")));

        WikiListResponse response = service.findWikis(1, 20, null, null, null, null);

        assertThat(response.items().get(0).summary())
                .isEqualTo("근무·휴가·복무 규정 요약(시연용)");
    }

    @Test
    @DisplayName("접근 가능한 Wiki는 본문을 Markdown 파일로 내려준다")
    void downloadsWikiFileForAccessibleWiki() throws Exception {
        Wiki wiki = wiki("ALL", 9L, "휴가 규정", 101L);
        wiki.assignStoragePath();
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        WikiScope scope = mock(WikiScope.class);
        given(wikiScopeRepository.findById("ALL")).willReturn(Optional.of(scope));
        given(wikiFileStorage.readWikiMarkdown(wiki.wikiPath())).willReturn("# 휴가 규정\n연차 안내");

        WikiFileDownload download = service.downloadFile(101L);

        assertThat(download.fileName()).isEqualTo("휴가 규정.md");
        assertThat(download.contentType()).isEqualTo("text/markdown; charset=UTF-8");
        String content = StreamUtils.copyToString(download.resource().getInputStream(), StandardCharsets.UTF_8);
        assertThat(content).isEqualTo("# 휴가 규정\n연차 안내");
    }

    @Test
    @DisplayName("접근 권한이 없는 부서 공개범위의 Wiki는 다운로드에서도 404로 감춘다")
    void rejectsDownloadForInaccessibleScope() throws Exception {
        Wiki wiki = wiki("D2", 9L, "부서 규정", 202L);
        wiki.assignStoragePath();
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(20L, CurrentMemberRole.EMPLOYEE));
        Department department = mock(Department.class);
        given(department.getId()).willReturn(3L);
        Member member = mock(Member.class);
        given(member.getDepartment()).willReturn(department);
        given(memberRepository.findById(20L)).willReturn(Optional.of(member));
        given(wikiRepository.findById(202L)).willReturn(Optional.of(wiki));
        WikiScope scope = mock(WikiScope.class);
        given(scope.visibilityType()).willReturn(WikiScopeVisibilityType.DEPARTMENT);
        given(scope.departmentRefs()).willReturn(List.of(2L));
        given(wikiScopeRepository.findById("D2")).willReturn(Optional.of(scope));

        assertThatThrownBy(() -> service.downloadFile(202L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_NOT_FOUND);
    }

    private Wiki wiki(String scopeKey, long categoryId, String title, long id) throws Exception {
        Wiki wiki = Wiki.create(scopeKey, categoryId, title);
        assignField(wiki, "id", id);
        assignField(wiki, "updatedAt", Instant.parse("2026-07-27T09:00:00Z"));
        return wiki;
    }

    private WikiCategory wikiCategory(long id, String name) throws Exception {
        WikiCategory category = WikiCategory.create("ALL", name, null);
        assignField(category, "id", id);
        return category;
    }

    private void assignField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
