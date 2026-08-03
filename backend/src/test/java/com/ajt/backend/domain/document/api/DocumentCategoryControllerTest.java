package com.ajt.backend.domain.document.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.global.auth.AccessTokenService;
import com.ajt.backend.global.auth.AuthCookieService;
import com.ajt.backend.global.auth.CsrfTokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false",
        "ajt.super-admin.email=admin@ajt.com"
})
@AutoConfigureMockMvc
@Transactional
@DisplayName("문서 카테고리 API")
class DocumentCategoryControllerTest {

    private static final String CSRF_TOKEN = "csrf-token";

    private final MockMvc mockMvc;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final DocumentCategoryRepository documentCategoryRepository;
    private final WikiScopeRepository wikiScopeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;

    @Autowired
    DocumentCategoryControllerTest(
            MockMvc mockMvc,
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            DocumentCategoryRepository documentCategoryRepository,
            WikiScopeRepository wikiScopeRepository,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService
    ) {
        this.mockMvc = mockMvc;
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.documentCategoryRepository = documentCategoryRepository;
        this.wikiScopeRepository = wikiScopeRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
    }

    @Test
    @DisplayName("GET /api/v1/document-categories는 로그인 사용자에게 카테고리 목록을 반환한다")
    void findCategoriesReturnsList() throws Exception {
        wikiScopeRepository.save(WikiScope.all());
        documentCategoryRepository.save(DocumentCategory.create("ALL", "공통", null));
        documentCategoryRepository.save(DocumentCategory.create("ALL", "규정", "회사 규정"));
        Member admin = memberRepository.save(approvedAdmin(departmentRepository.save(new Department("개발부"))));

        mockMvc.perform(get("/api/v1/document-categories")
                        .cookie(accessTokenCookie(admin))
                        .param("scopeKey", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].name").value("공통"));
    }

    @Test
    @DisplayName("GET /api/v1/document-categories는 로그인하지 않으면 401을 반환한다")
    void findCategoriesRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/document-categories")
                        .param("scopeKey", "ALL"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
    }

    @Test
    @DisplayName("POST /api/v1/document-categories는 관리자가 카테고리를 생성한다")
    void createCategoryCreatesCategory() throws Exception {
        Member admin = memberRepository.save(approvedAdmin(departmentRepository.save(new Department("개발부"))));

        mockMvc.perform(post("/api/v1/document-categories")
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scopeKey": "ALL",
                                  "name": "공통 규정",
                                  "description": "전체 공개 규정"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scopeKey").value("ALL"))
                .andExpect(jsonPath("$.name").value("공통 규정"));
    }

    @Test
    @DisplayName("POST /api/v1/document-categories는 일반 사용자를 403으로 거절한다")
    void createCategoryRejectsEmployee() throws Exception {
        Department department = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(approvedEmployee(department));

        mockMvc.perform(post("/api/v1/document-categories")
                        .cookie(accessTokenCookie(employee))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scopeKey": "ALL",
                                  "name": "공통 규정"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_REQUIRED"));
    }

    @Test
    @DisplayName("PATCH /api/v1/document-categories/{categoryId}는 카테고리를 수정한다")
    void updateCategoryChangesCategory() throws Exception {
        wikiScopeRepository.save(WikiScope.all());
        DocumentCategory category = documentCategoryRepository.save(DocumentCategory.create("ALL", "기존", null));
        Member admin = memberRepository.save(approvedAdmin(departmentRepository.save(new Department("개발부"))));

        mockMvc.perform(patch("/api/v1/document-categories/{categoryId}", category.id())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "변경",
                                  "description": "변경 설명"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("변경"))
                .andExpect(jsonPath("$.description").value("변경 설명"));
    }

    @Test
    @DisplayName("DELETE /api/v1/document-categories/{categoryId}는 사용하지 않는 카테고리를 삭제한다")
    void deleteCategoryDeletesCategory() throws Exception {
        wikiScopeRepository.save(WikiScope.all());
        DocumentCategory category = documentCategoryRepository.save(DocumentCategory.create("ALL", "공통", null));
        Member admin = memberRepository.save(approvedAdmin(departmentRepository.save(new Department("개발부"))));

        mockMvc.perform(delete("/api/v1/document-categories/{categoryId}", category.id())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN))
                .andExpect(status().isNoContent());
    }

    private Cookie accessTokenCookie(Member member) {
        return new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member));
    }

    private Cookie csrfCookie() {
        return new Cookie(CsrfTokenService.CSRF_COOKIE_NAME, CSRF_TOKEN);
    }

    private Member approvedAdmin(Department department) {
        return Member.approved(
                department,
                "admin@ajt.com",
                "관리자",
                passwordEncoder.encode("password123!"),
                "AJT-2026-9001",
                Role.ADMIN
        );
    }

    private Member approvedEmployee(Department department) {
        return Member.approvedEmployee(
                department,
                "employee@ajt.com",
                "홍길동",
                passwordEncoder.encode("password123!"),
                "AJT-2026-0001"
        );
    }
}
