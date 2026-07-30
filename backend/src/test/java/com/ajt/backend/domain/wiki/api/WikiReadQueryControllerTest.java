package com.ajt.backend.domain.wiki.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.global.auth.AccessTokenService;
import com.ajt.backend.global.auth.AuthCookieService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wiki 조회(공간·카테고리·상세) 컨트롤러 통합 테스트입니다.
 * MockMvc로 인증·응답 형식·오류 코드를 검증합니다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class WikiReadQueryControllerTest {

    private final MockMvc mockMvc;
    private final WikiScopeRepository wikiScopeRepository;
    private final WikiCategoryRepository wikiCategoryRepository;
    private final WikiRepository wikiRepository;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;

    @Autowired
    WikiReadQueryControllerTest(
            MockMvc mockMvc,
            WikiScopeRepository wikiScopeRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiRepository wikiRepository,
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService
    ) {
        this.mockMvc = mockMvc;
        this.wikiScopeRepository = wikiScopeRepository;
        this.wikiCategoryRepository = wikiCategoryRepository;
        this.wikiRepository = wikiRepository;
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
    }

    @Test
    @DisplayName("GET /api/v1/wiki-spaces는 로그인 사용자에게 접근 가능한 공간 목록을 반환한다")
    void wikiSpacesReturnsAccessibleSpaces() throws Exception {
        // 전체 공개 공간이 하나 있으면 사원에게 목록으로 나온다.
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev));
        wikiScopeRepository.save(WikiScope.all());

        mockMvc.perform(get("/api/v1/wiki-spaces").cookie(accessTokenCookie(employee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].scopeKey").value("ALL"))
                .andExpect(jsonPath("$.items[0].visibilityType").value("all"));
    }

    @Test
    @DisplayName("GET /api/v1/wiki-spaces는 인증 쿠키가 없으면 401을 반환한다")
    void wikiSpacesRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/wiki-spaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
    }

    @Test
    @DisplayName("GET /api/v1/wiki-categories는 공간의 카테고리 목록을 반환한다")
    void wikiCategoriesReturnsList() throws Exception {
        // 전체 공개 공간의 카테고리 한 건을 반환한다.
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev));
        wikiScopeRepository.save(WikiScope.all());
        wikiCategoryRepository.save(WikiCategory.create("ALL", "휴가 및 근태", "AI가 분류한 휴가·근태 규정"));

        mockMvc.perform(get("/api/v1/wiki-categories")
                        .param("scopeKey", "ALL")
                        .cookie(accessTokenCookie(employee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("휴가 및 근태"))
                .andExpect(jsonPath("$.items[0].scopeKey").value("ALL"));
    }

    @Test
    @DisplayName("GET /api/v1/wiki-categories는 scopeKey 형식이 잘못되면 400을 반환한다")
    void wikiCategoriesRejectsInvalidScopeKey() throws Exception {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev));

        mockMvc.perform(get("/api/v1/wiki-categories")
                        .param("scopeKey", "!!!")
                        .cookie(accessTokenCookie(employee)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCOPE_KEY"));
    }

    @Test
    @DisplayName("GET /api/v1/wikis/{wikiId}는 접근 가능한 Wiki 상세를 반환한다")
    void wikiDetailReturnsWiki() throws Exception {
        // 전체 공개 공간의 Wiki 상세를 조회한다.
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev));
        wikiScopeRepository.save(WikiScope.all());
        WikiCategory category = wikiCategoryRepository.save(WikiCategory.create("ALL", "휴가 및 근태", null));
        Wiki wiki = wikiRepository.save(Wiki.create("ALL", category.id(), "휴가 규정"));
        wiki.assignStoragePath();

        mockMvc.perform(get("/api/v1/wikis/{wikiId}", wiki.id()).cookie(accessTokenCookie(employee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wikiId").value(String.valueOf(wiki.id())))
                .andExpect(jsonPath("$.title").value("휴가 규정"))
                .andExpect(jsonPath("$.scopeKey").value("ALL"));
    }

    @Test
    @DisplayName("GET /api/v1/wikis/{wikiId}는 존재하지 않는 Wiki에 404를 반환한다")
    void wikiDetailReturnsNotFound() throws Exception {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev));

        mockMvc.perform(get("/api/v1/wikis/{wikiId}", 999999).cookie(accessTokenCookie(employee)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WIKI_NOT_FOUND"));
    }

    private Cookie accessTokenCookie(Member member) {
        return new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member));
    }

    private Member employee(Department department) {
        return Member.approvedEmployee(department, "emp@ajt.com", "홍길동",
                passwordEncoder.encode("password123!"), "AJT-2026-0001");
    }
}
