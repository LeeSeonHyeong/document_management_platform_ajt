package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.wiki.api.WikiCategoryListResponse;
import com.ajt.backend.domain.wiki.api.WikiDetailResponse;
import com.ajt.backend.domain.wiki.api.WikiSpaceListResponse;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wiki 조회(공간·카테고리·상세) 서비스 통합 테스트입니다.
 * 실제 H2 DB로 접근 권한 필터(FR-ACL-002)와 공간·카테고리·상세 매핑을 검증합니다.
 * 현재 사용자는 SecurityContext에 심어 CurrentMemberProvider가 읽도록 합니다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false",
        "ajt.super-admin.email=admin@ajt.com"
})
@Transactional
class WikiReadQueryServiceTest {

    private final WikiQueryService wikiQueryService;
    private final WikiScopeRepository wikiScopeRepository;
    private final WikiRepository wikiRepository;
    private final WikiCategoryRepository wikiCategoryRepository;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    WikiReadQueryServiceTest(
            WikiQueryService wikiQueryService,
            WikiScopeRepository wikiScopeRepository,
            WikiRepository wikiRepository,
            WikiCategoryRepository wikiCategoryRepository,
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.wikiQueryService = wikiQueryService;
        this.wikiScopeRepository = wikiScopeRepository;
        this.wikiRepository = wikiRepository;
        this.wikiCategoryRepository = wikiCategoryRepository;
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("공간 목록은 사원에게 전체 공개 공간과 본인 부서가 포함된 공간만 보여준다")
    void findSpacesShowsAllAndOwnDepartmentForEmployee() {
        // 개발부 사원 기준으로, 전체(ALL)와 개발부 포함 공간은 보이고 인사부 전용 공간은 숨겨져야 한다.
        Department dev = departmentRepository.save(new Department("개발부"));
        Department hr = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(employee(dev, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        wikiScopeRepository.save(WikiScope.all());
        WikiScope devScope = wikiScopeRepository.save(WikiScope.department(List.of(dev.getId())));
        wikiScopeRepository.save(WikiScope.department(List.of(hr.getId())));
        authenticate(employee);

        WikiSpaceListResponse response = wikiQueryService.findAccessibleSpaces();

        assertThat(response.items()).extracting("scopeKey")
                .containsExactlyInAnyOrder("ALL", devScope.scopeKey());
    }

    @Test
    @DisplayName("공간 목록은 관리자에게 모든 공간을 보여준다")
    void findSpacesShowsAllForAdmin() {
        // 관리자는 부서와 무관하게 전체·부서 공간을 모두 볼 수 있어야 한다.
        Department dev = departmentRepository.save(new Department("개발부"));
        Department hr = departmentRepository.save(new Department("인사부"));
        Member admin = memberRepository.save(admin(dev, "admin@ajt.com", "김관리"));
        wikiScopeRepository.save(WikiScope.all());
        wikiScopeRepository.save(WikiScope.department(List.of(hr.getId())));
        authenticate(admin);

        WikiSpaceListResponse response = wikiQueryService.findAccessibleSpaces();

        assertThat(response.items()).hasSize(2);
    }

    @Test
    @DisplayName("공간 응답에는 부서 표시 이름과 Wiki 개수가 담긴다")
    void findSpacesIncludesDisplayNameAndWikiCount() {
        // 개발부+인사부 공간에 Wiki 2건을 두면 표시 이름과 개수가 응답에 반영돼야 한다.
        Department dev = departmentRepository.save(new Department("개발부"));
        Department hr = departmentRepository.save(new Department("인사부"));
        Member admin = memberRepository.save(admin(dev, "admin@ajt.com", "김관리"));
        WikiScope scope = wikiScopeRepository.save(WikiScope.department(List.of(dev.getId(), hr.getId())));
        saveWiki(scope.scopeKey(), 1L, "휴가 규정");
        saveWiki(scope.scopeKey(), 1L, "근태 관리");
        authenticate(admin);

        WikiSpaceListResponse response = wikiQueryService.findAccessibleSpaces();

        assertThat(response.items()).singleElement().satisfies(space -> {
            assertThat(space.visibilityType()).isEqualTo("department");
            assertThat(space.displayName()).isEqualTo("개발부 + 인사부");
            assertThat(space.wikiCount()).isEqualTo(2);
            assertThat(space.departments()).extracting("name").containsExactly("개발부", "인사부");
        });
    }

    @Test
    @DisplayName("카테고리 목록은 접근 가능한 공간의 카테고리를 이름순으로 반환한다")
    void findCategoriesReturnsSortedForAccessibleScope() {
        // 전체 공개 공간의 카테고리 두 개를 이름 오름차순으로 반환한다.
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        wikiScopeRepository.save(WikiScope.all());
        wikiCategoryRepository.save(WikiCategory.create("ALL", "휴가 및 근태", null));
        wikiCategoryRepository.save(WikiCategory.create("ALL", "보안 정책", null));
        authenticate(employee);

        WikiCategoryListResponse response = wikiQueryService.findCategories("ALL");

        assertThat(response.items()).extracting("name").containsExactly("보안 정책", "휴가 및 근태");
    }

    @Test
    @DisplayName("카테고리 목록은 접근 권한이 없는 공간을 404로 숨긴다")
    void findCategoriesHidesInaccessibleScope() {
        // 개발부 사원이 인사부 전용 공간의 카테고리를 조회하면 404가 나야 한다.
        Department dev = departmentRepository.save(new Department("개발부"));
        Department hr = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(employee(dev, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        WikiScope hrScope = wikiScopeRepository.save(WikiScope.department(List.of(hr.getId())));
        authenticate(employee);

        assertThatThrownBy(() -> wikiQueryService.findCategories(hrScope.scopeKey()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_SCOPE_NOT_FOUND);
    }

    @Test
    @DisplayName("카테고리 목록은 scopeKey 형식이 잘못되면 400을 반환한다")
    void findCategoriesRejectsInvalidScopeKey() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member admin = memberRepository.save(admin(dev, "admin@ajt.com", "김관리"));
        authenticate(admin);

        assertThatThrownBy(() -> wikiQueryService.findCategories("잘못된-키"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SCOPE_KEY);
    }

    @Test
    @DisplayName("Wiki 상세는 접근 가능한 Wiki의 제목·공간·카테고리를 반환한다")
    void getWikiReturnsDetail() {
        // 전체 공개 공간의 Wiki를 사원이 조회하면 상세 정보가 나와야 한다.
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        wikiScopeRepository.save(WikiScope.all());
        WikiCategory category = wikiCategoryRepository.save(WikiCategory.create("ALL", "휴가 및 근태", null));
        Wiki wiki = saveWiki("ALL", category.id(), "휴가 규정");
        authenticate(employee);

        WikiDetailResponse response = wikiQueryService.getWiki(wiki.id());

        assertThat(response.wikiId()).isEqualTo(String.valueOf(wiki.id()));
        assertThat(response.title()).isEqualTo("휴가 규정");
        assertThat(response.scopeKey()).isEqualTo("ALL");
        assertThat(response.category().name()).isEqualTo("휴가 및 근태");
    }

    @Test
    @DisplayName("Wiki 상세는 접근 권한이 없는 공간의 Wiki를 404로 숨긴다")
    void getWikiHidesInaccessibleWiki() {
        // 개발부 사원이 인사부 전용 공간의 Wiki를 조회하면 404가 나야 한다.
        Department dev = departmentRepository.save(new Department("개발부"));
        Department hr = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(employee(dev, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        WikiScope hrScope = wikiScopeRepository.save(WikiScope.department(List.of(hr.getId())));
        Wiki wiki = saveWiki(hrScope.scopeKey(), 1L, "인사 규정");
        authenticate(employee);

        assertThatThrownBy(() -> wikiQueryService.getWiki(wiki.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_NOT_FOUND);
    }

    @Test
    @DisplayName("Wiki 상세는 존재하지 않는 Wiki를 404로 반환한다")
    void getWikiRejectsUnknownWiki() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        authenticate(employee);

        assertThatThrownBy(() -> wikiQueryService.getWiki(999999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_NOT_FOUND);
    }

    private Wiki saveWiki(String scopeKey, long categoryId, String title) {
        Wiki wiki = wikiRepository.save(Wiki.create(scopeKey, categoryId, title));
        wiki.assignStoragePath();
        return wiki;
    }

    private void authenticate(Member member) {
        AuthenticatedMember principal = new AuthenticatedMember(member.getId(), member.getEmail(), member.getRole());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private Member admin(Department department, String email, String name) {
        return Member.approved(department, email, name, passwordEncoder.encode("password123!"),
                "AJT-2026-" + Math.abs(email.hashCode() % 10000), Role.ADMIN);
    }

    private Member employee(Department department, String email, String name, String employeeNo) {
        return Member.approvedEmployee(department, email, name, passwordEncoder.encode("password123!"), employeeNo);
    }
}
