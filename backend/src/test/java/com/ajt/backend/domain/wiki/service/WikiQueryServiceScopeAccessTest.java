package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.service.CurrentMember;
import com.ajt.backend.domain.document.service.CurrentMemberProvider;
import com.ajt.backend.domain.document.service.CurrentMemberRole;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.wiki.api.WikiListResponse;
import com.ajt.backend.domain.wiki.api.WikiSummaryResponse;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wiki 목록·검색 접근권한(WIKI-01/FR-ACL-002)을 실제 DB 쿼리로 검증하는 통합테스트입니다.
 * 단위 테스트는 findAll을 목으로 대체해 실제 scope_key IN 필터가 실행되지 않으므로,
 * "권한 밖 Wiki가 정말 제외되는지"는 실 데이터로만 확인할 수 있습니다.
 * 신원(로그인 사용자)만 목으로 제어하고, Wiki·공개범위 조회는 실제 리포지터리를 사용합니다.
 * (요약 목차 파일은 이 테스트 범위가 아니라 파일 저장소를 목으로 둔다.)
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@Transactional
@DisplayName("Wiki 목록·검색 접근권한 통합테스트")
class WikiQueryServiceScopeAccessTest {

    @Autowired
    private WikiQueryService wikiQueryService;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private WikiScopeRepository wikiScopeRepository;
    @Autowired
    private WikiCategoryRepository wikiCategoryRepository;
    @Autowired
    private WikiRepository wikiRepository;

    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;
    // 요약 목차(index.md) 파일 읽기는 이 테스트의 관심사가 아니므로 목으로 둔다(기본 null → 요약 없음).
    @MockitoBean
    private WikiFileStorage wikiFileStorage;

    private long employeeId;
    private long superAdminId;
    private long deptManagerId;
    private String scopeD2;
    private String scopeD3;
    private String scopeD2D3;

    @BeforeEach
    void seed() {
        Department department2 = departmentRepository.save(new Department("영업부"));
        Department department3 = departmentRepository.save(new Department("기획부"));
        employeeId = memberRepository.save(Member.approvedEmployee(
                department2, "emp@ajt.com", "사원", "hash", "AJT-2026-0001")).getId();
        // 최고관리자(설정 이메일 기본값 superadmin@ajt.com)와 department2 담당 부서관리자 (S15P11B106-199)
        superAdminId = memberRepository.save(Member.approved(
                department2, "superadmin@ajt.com", "최고관리자", "hash", "AJT-2026-9999", Role.ADMIN)).getId();
        Member deptManager = memberRepository.save(Member.approved(
                department2, "mgr@ajt.com", "부서관리자", "hash", "AJT-2026-0002", Role.ADMIN));
        department2.assignManager(deptManager);
        departmentRepository.save(department2);
        deptManagerId = deptManager.getId();

        wikiScopeRepository.save(WikiScope.all());
        scopeD2 = wikiScopeRepository.save(WikiScope.department(List.of(department2.getId()))).scopeKey();
        scopeD3 = wikiScopeRepository.save(WikiScope.department(List.of(department3.getId()))).scopeKey();
        // 본인 부서(D2)가 포함된 복수부서 공개 범위(S15P11B106-229). 사원·부서관리자 모두 조회 가능해야 한다.
        scopeD2D3 = wikiScopeRepository.save(
                WikiScope.department(List.of(department2.getId(), department3.getId()))).scopeKey();

        long categoryAll = wikiCategoryRepository.save(WikiCategory.create("ALL", "공통", null)).id();
        long categoryD2 = wikiCategoryRepository.save(WikiCategory.create(scopeD2, "영업", null)).id();
        long categoryD3 = wikiCategoryRepository.save(WikiCategory.create(scopeD3, "기획", null)).id();
        long categoryD2D3 = wikiCategoryRepository.save(WikiCategory.create(scopeD2D3, "영업기획", null)).id();

        wikiRepository.save(Wiki.create("ALL", categoryAll, "전사 위키"));
        wikiRepository.save(Wiki.create(scopeD2, categoryD2, "영업 위키"));
        wikiRepository.save(Wiki.create(scopeD3, categoryD3, "기획 위키"));
        wikiRepository.save(Wiki.create(scopeD2D3, categoryD2D3, "영업+기획 위키"));
    }

    @Test
    @DisplayName("사원은 전체 공개(ALL)·본인 소속 부서(D2)·본인 부서 포함 복수부서(D2+D3) Wiki를 보고, 타부서(D3)는 제외된다")
    void employeeSeesOnlyAccessibleScopes() {
        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(employeeId, CurrentMemberRole.EMPLOYEE));

        WikiListResponse response = wikiQueryService.findWikis(1, 100, null, null, null, null);

        List<String> scopeKeys = response.items().stream()
                .map(WikiSummaryResponse::scopeKey)
                .toList();
        assertThat(scopeKeys).containsExactlyInAnyOrder("ALL", scopeD2, scopeD2D3);
        assertThat(scopeKeys).doesNotContain(scopeD3);
    }

    @Test
    @DisplayName("최고관리자는 접근 제한 없이 모든 공개범위의 Wiki를 본다")
    void adminSeesAllScopes() {
        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(superAdminId, CurrentMemberRole.ADMIN));

        WikiListResponse response = wikiQueryService.findWikis(1, 100, null, null, null, null);

        List<String> scopeKeys = response.items().stream()
                .map(WikiSummaryResponse::scopeKey)
                .toList();
        assertThat(scopeKeys).containsExactlyInAnyOrder("ALL", scopeD2, scopeD3, scopeD2D3);
    }

    @Test
    @DisplayName("부서관리자는 사원과 동일하게 전체(ALL)·본인 부서(D2)·본인 부서 포함 복수부서(D2+D3)를 보고, 타부서(D3)는 제외된다(S15P11B106-229)")
    void departmentManagerSeesSameScopesAsEmployee() {
        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(deptManagerId, CurrentMemberRole.ADMIN));

        WikiListResponse response = wikiQueryService.findWikis(1, 100, null, null, null, null);

        List<String> scopeKeys = response.items().stream()
                .map(WikiSummaryResponse::scopeKey)
                .toList();
        assertThat(scopeKeys).containsExactlyInAnyOrder("ALL", scopeD2, scopeD2D3);
        assertThat(scopeKeys).doesNotContain(scopeD3);
    }
}
