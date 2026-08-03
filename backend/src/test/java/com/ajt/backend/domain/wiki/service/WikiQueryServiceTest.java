package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

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
    @DisplayName("관리자는 접근 제한 없이 목록을 조회하고, 요약은 목차에서 채운다")
    void adminListsWikis() throws Exception {
        Wiki wiki = wiki("ALL", 9L, "휴가 규정", 101L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(wikiRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(wiki), PageRequest.of(0, 20), 1));
        given(wikiCategoryRepository.findAllById(any())).willReturn(List.of(wikiCategory(9L, "휴가 및 근태")));
        given(wikiFileStorage.readIndex("ALL"))
                .willReturn("# 목차\n\n- [휴가 규정](pages/101.md) — 연차와 반차 사용 기준");

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
        given(wikiFileStorage.readIndex("D2"))
                .willReturn("# 목차\n\n- [부서 규정](pages/202.md) — 부서 전용 규정");

        WikiListResponse response = service.findWikis(1, 20, null, null, null, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).wikiId()).isEqualTo("202");
        assertThat(response.items().get(0).summary()).isEqualTo("부서 전용 규정");
        verify(memberRepository).findById(20L);
        verify(wikiScopeRepository).findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT);
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
