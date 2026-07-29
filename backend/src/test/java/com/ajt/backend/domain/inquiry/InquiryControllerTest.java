package com.ajt.backend.domain.inquiry;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.inquiry.dto.InquiryCreateRequest;
import com.ajt.backend.domain.inquiry.dto.InquiryResponse;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.global.auth.AccessTokenService;
import com.ajt.backend.global.auth.AuthCookieService;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.auth.CsrfTokenService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문의 컨트롤러 통합 테스트입니다.
 * MockMvc로 실제 HTTP 요청을 흉내내어 인증·CSRF·응답 형식과 오류 코드를 검증합니다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class InquiryControllerTest {

    private static final String CSRF_TOKEN = "csrf-token";

    private final MockMvc mockMvc;
    private final InquiryService inquiryService;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;

    @Autowired
    InquiryControllerTest(
            MockMvc mockMvc,
            InquiryService inquiryService,
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService
    ) {
        this.mockMvc = mockMvc;
        this.inquiryService = inquiryService;
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
    }

    @Test
    @DisplayName("GET /api/v1/inquiry-assignees는 로그인 사용자에게 담당자 후보를 반환한다")
    void assigneesReturnsCandidates() throws Exception {
        // 사원이 담당자 후보(승인·활성 관리자) 목록을 조회할 수 있어야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));

        mockMvc.perform(get("/api/v1/inquiry-assignees").cookie(accessTokenCookie(employee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("김관리"))
                .andExpect(jsonPath("$.items[0].department.name").value("인사부"));
    }

    @Test
    @DisplayName("GET /api/v1/inquiry-assignees는 인증 쿠키가 없으면 401을 반환한다")
    void assigneesRejectsMissingToken() throws Exception {
        // 인증 쿠키가 없으면 담당자 후보를 볼 수 없다.
        mockMvc.perform(get("/api/v1/inquiry-assignees"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
    }

    @Test
    @DisplayName("POST /api/v1/inquiries는 담당자를 지정한 문의를 등록하고 201을 반환한다")
    void createInquiryReturnsCreated() throws Exception {
        // 첨부 이미지 한 장과 함께 문의를 등록하면 201과 pending 상태 응답이 온다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        MockMultipartFile image = new MockMultipartFile("attachments", "photo.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/inquiries")
                        .file(image)
                        .param("assigneeId", String.valueOf(admin.getId()))
                        .param("title", "연차 문의")
                        .param("content", "연차 사용 기준이 궁금합니다.")
                        .param("priority", "normal")
                        .cookie(accessTokenCookie(employee))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.assignee.name").value("김관리"))
                .andExpect(jsonPath("$.attachments", hasSize(1)))
                .andExpect(jsonPath("$.answer").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/inquiries는 담당자 자격이 없는 회원을 지정하면 409를 반환한다")
    void createInquiryRejectsIneligibleAssignee() throws Exception {
        // 사원을 담당자로 지정하면 409 오류가 나야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member notAdmin = memberRepository.save(approvedEmployee(department, "other@ajt.com", "박사원", "AJT-2026-0002"));

        mockMvc.perform(multipart("/api/v1/inquiries")
                        .param("assigneeId", String.valueOf(notAdmin.getId()))
                        .param("title", "연차 문의")
                        .param("content", "내용입니다")
                        .param("priority", "normal")
                        .cookie(accessTokenCookie(employee))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_ASSIGNEE_NOT_ELIGIBLE"));
    }

    @Test
    @DisplayName("POST /api/v1/inquiries는 제목이 비어 있으면 400 INVALID_INQUIRY를 반환한다")
    void createInquiryRejectsBlankTitle() throws Exception {
        // 제목 없이 등록하면 입력값 오류로 막힌다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));

        mockMvc.perform(multipart("/api/v1/inquiries")
                        .param("assigneeId", String.valueOf(admin.getId()))
                        .param("title", "")
                        .param("content", "내용입니다")
                        .param("priority", "normal")
                        .cookie(accessTokenCookie(employee))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INQUIRY"));
    }

    @Test
    @DisplayName("GET /api/v1/inquiries는 로그인 사용자의 문의 목록을 페이지 형식으로 반환한다")
    void inquiriesReturnsPagedList() throws Exception {
        // 사원이 등록한 문의가 목록에 페이지 정보와 함께 나온다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        inquiryService.create(login(employee), request(admin.getId()));

        mockMvc.perform(get("/api/v1/inquiries").cookie(accessTokenCookie(employee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/inquiries/{inquiryId}는 존재하지 않는 문의에 404를 반환한다")
    void inquiryDetailReturnsNotFound() throws Exception {
        // 없는 문의 ID로 상세 조회하면 404가 나야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));

        mockMvc.perform(get("/api/v1/inquiries/{inquiryId}", 999999).cookie(accessTokenCookie(employee)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
    }

    @Test
    @DisplayName("PUT /api/v1/inquiries/{inquiryId}/answer는 지정 담당자의 답변을 저장한다")
    void upsertAnswerSavesReply() throws Exception {
        // 담당자가 답변을 작성하면 답변 내용이 응답으로 돌아온다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        InquiryResponse created = inquiryService.create(login(employee), request(admin.getId()));

        mockMvc.perform(put("/api/v1/inquiries/{inquiryId}/answer", created.inquiryId())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "content": "규정에 따라 사용 가능합니다" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("규정에 따라 사용 가능합니다"))
                .andExpect(jsonPath("$.adminName").value("김관리"));
    }

    @Test
    @DisplayName("PUT /api/v1/inquiries/{inquiryId}/answer는 지정 담당자가 아니면 403을 반환한다")
    void upsertAnswerRejectsNonAssignee() throws Exception {
        // 담당자가 아닌 다른 관리자가 답변을 시도하면 403이 나야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        Member otherAdmin = memberRepository.save(approvedAdmin(department, "admin2@ajt.com", "이관리"));
        InquiryResponse created = inquiryService.create(login(employee), request(admin.getId()));

        mockMvc.perform(put("/api/v1/inquiries/{inquiryId}/answer", created.inquiryId())
                        .cookie(accessTokenCookie(otherAdmin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "content": "제가 답변합니다" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INQUIRY_FORBIDDEN"));
    }

    @Test
    @DisplayName("PUT /api/v1/inquiries/{inquiryId}/answer는 답변 내용이 비어 있으면 400을 반환한다")
    void upsertAnswerRejectsBlankContent() throws Exception {
        // 빈 답변은 검증에서 걸러진다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        InquiryResponse created = inquiryService.create(login(employee), request(admin.getId()));

        mockMvc.perform(put("/api/v1/inquiries/{inquiryId}/answer", created.inquiryId())
                        .cookie(accessTokenCookie(admin))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "content": "" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /api/v1/inquiries/{inquiryId}는 작성자의 문의를 삭제하고 204를 반환한다")
    void deleteInquiryReturnsNoContent() throws Exception {
        // 작성자가 자신의 문의를 삭제하면 204가 나야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member employee = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        InquiryResponse created = inquiryService.create(login(employee), request(admin.getId()));

        mockMvc.perform(delete("/api/v1/inquiries/{inquiryId}", created.inquiryId())
                        .cookie(accessTokenCookie(employee))
                        .cookie(csrfCookie())
                        .header(CsrfTokenService.CSRF_HEADER_NAME, CSRF_TOKEN))
                .andExpect(status().isNoContent());
    }

    private AuthenticatedMember login(Member member) {
        return new AuthenticatedMember(member.getId(), member.getEmail(), member.getRole());
    }

    private InquiryCreateRequest request(long assigneeId) {
        return InquiryCreateRequest.of(assigneeId, "연차 문의", "연차 사용 기준이 궁금합니다.", "normal", List.of());
    }

    private Cookie accessTokenCookie(Member member) {
        return new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, accessTokenService.createAccessToken(member));
    }

    private Cookie csrfCookie() {
        return new Cookie(CsrfTokenService.CSRF_COOKIE_NAME, CSRF_TOKEN);
    }

    private Member approvedAdmin(Department department, String email, String name) {
        return Member.approved(department, email, name, passwordEncoder.encode("password123!"),
                "AJT-2026-" + Math.abs(email.hashCode() % 10000), Role.ADMIN);
    }

    private Member approvedEmployee(Department department, String email, String name, String employeeNo) {
        return Member.approvedEmployee(department, email, name, passwordEncoder.encode("password123!"), employeeNo);
    }
}
