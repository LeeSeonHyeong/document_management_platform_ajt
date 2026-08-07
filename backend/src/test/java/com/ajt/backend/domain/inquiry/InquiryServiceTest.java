package com.ajt.backend.domain.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.inquiry.dto.InquiryAnswerResponse;
import com.ajt.backend.domain.inquiry.dto.InquiryAssigneeListResponse;
import com.ajt.backend.domain.inquiry.dto.InquiryAttachmentDownload;
import com.ajt.backend.domain.inquiry.dto.InquiryCreateRequest;
import com.ajt.backend.domain.inquiry.dto.InquiryListResponse;
import com.ajt.backend.domain.inquiry.dto.InquiryResponse;
import com.ajt.backend.domain.member.AccountStatus;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 문의 서비스 통합 테스트입니다.
 * 실제 H2 DB와 저장소 빈을 사용해 등록·조회·삭제·답변 정책이 요구사항대로 동작하는지 검증합니다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@Transactional
class InquiryServiceTest {

    // 수정(S15P11B106-146): 최고관리자는 설정 이메일(ajt.super-admin.email 기본값)로 식별한다.
    private static final String SUPER_ADMIN_EMAIL = "superadmin@ajt.com";

    private final InquiryService inquiryService;
    private final InquiryRepository inquiryRepository;
    private final InquiryReplyRepository inquiryReplyRepository;
    private final DepartmentRepository departmentRepository;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;

    @Autowired
    InquiryServiceTest(
            InquiryService inquiryService,
            InquiryRepository inquiryRepository,
            InquiryReplyRepository inquiryReplyRepository,
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            EntityManager entityManager
    ) {
        this.inquiryService = inquiryService;
        this.inquiryRepository = inquiryRepository;
        this.inquiryReplyRepository = inquiryReplyRepository;
        this.departmentRepository = departmentRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.entityManager = entityManager;
    }

    @Test
    @DisplayName("담당자 후보 조회는 승인·활성 상태의 관리자만 반환하고 사원·비활성 관리자는 제외한다")
    void findAssigneesReturnsOnlyActiveApprovedAdmins() {
        // 부서 하나에 관리자 1명, 사원 1명, 비활성 관리자 1명을 만든다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member inactiveAdmin = memberRepository.save(approvedAdmin(department, "old@ajt.com", "이관리"));
        // 부서장이 아니므로 담당 부서(자동 해제 대상)는 null이다.
        inactiveAdmin.updateByAdmin(null, null, null, AccountStatus.INACTIVE, null); // 비활성 관리자는 후보에서 빠져야 한다.

        InquiryAssigneeListResponse response = inquiryService.findAssignees(login(admin), null);

        // 승인·활성 관리자(김관리) 한 명만 후보로 나온다.
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().name()).isEqualTo("김관리");
        assertThat(response.items().getFirst().department().name()).isEqualTo("인사부");
    }

    @Test
    @DisplayName("담당자 후보 조회는 keyword로 관리자 이름을 부분 검색한다")
    void findAssigneesFiltersByKeyword() {
        // 이름이 다른 관리자 두 명을 만들고 keyword로 한 명만 걸러지는지 본다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member kim = memberRepository.save(approvedAdmin(department, "kim@ajt.com", "김관리"));
        memberRepository.save(approvedAdmin(department, "lee@ajt.com", "이담당"));

        InquiryAssigneeListResponse response = inquiryService.findAssignees(login(kim), "김");

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().name()).isEqualTo("김관리");
    }

    @Test
    @DisplayName("문의 등록은 담당자를 지정해 저장하고 초기 상태를 pending으로 만든다")
    void createStoresInquiryAsPending() {
        // 등록자(사원)와 담당자(관리자)를 준비한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));

        InquiryResponse response = inquiryService.create(login(author), request(assignee.getId(), List.of()));

        // 응답에 작성자·담당자·상태가 요구사항대로 담긴다.
        assertThat(response.status()).isEqualTo("pending");
        assertThat(response.author().name()).isEqualTo("홍길동");
        assertThat(response.assignee().name()).isEqualTo("김관리");
        assertThat(response.assignee().department().name()).isEqualTo("인사부");
        assertThat(response.answer()).isNull();
    }

    @Test
    @DisplayName("문의 등록은 승인·활성 관리자가 아닌 회원을 담당자로 지정하면 409로 거부한다")
    void createRejectsIneligibleAssignee() {
        // 담당자로 사원을 지정하면 담당자 자격이 없어 등록이 막혀야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member notAdmin = memberRepository.save(approvedEmployee(department, "other@ajt.com", "박사원", "AJT-2026-0002"));

        assertThatThrownBy(() -> inquiryService.create(login(author), request(notAdmin.getId(), List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INQUIRY_ASSIGNEE_NOT_ELIGIBLE);
    }

    @Test
    @DisplayName("문의 등록 시 첨부 이미지는 JSON 컬럼에 저장되고 다시 조회해도 유지된다")
    void createPersistsAttachmentsAsJson() {
        // 이미지 한 장을 첨부해 등록한 뒤, 영속성 컨텍스트를 비우고 DB에서 다시 읽어 JSON 저장을 확인한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        MockMultipartFile image = new MockMultipartFile("attachments", "photo.png", "image/png", new byte[]{1, 2, 3});

        InquiryResponse created = inquiryService.create(login(author), request(assignee.getId(), List.of(image)));

        entityManager.flush();
        entityManager.clear();
        Inquiry reloaded = inquiryRepository.findById(Long.valueOf(created.inquiryId())).orElseThrow();
        assertThat(reloaded.getAttachmentRefs()).hasSize(1);
        assertThat(reloaded.getAttachmentRefs().getFirst().originalFileName()).isEqualTo("photo.png");
        assertThat(reloaded.getAttachmentRefs().getFirst().mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("문의 목록은 사원에게 본인이 등록한 문의만 보여준다")
    void findInquiriesShowsOnlyOwnForEmployee() {
        // 사원 두 명이 각각 문의를 등록하면, 자기 문의만 조회돼야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member other = memberRepository.save(approvedEmployee(department, "other@ajt.com", "박사원", "AJT-2026-0002"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        inquiryService.create(login(author), request(assignee.getId(), List.of()));
        inquiryService.create(login(other), request(assignee.getId(), List.of()));

        InquiryListResponse response = inquiryService.findInquiries(
                login(author), null, null, null, null, null, null, null, null, null);

        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.items().getFirst().author().name()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("문의 목록은 관리자에게 본인이 담당자로 지정된 문의만 보여준다")
    void findInquiriesShowsOnlyAssignedForAdmin() {
        // 담당자가 다른 두 문의 중, 로그인한 관리자가 담당인 문의만 조회돼야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        Member otherAdmin = memberRepository.save(approvedAdmin(department, "admin2@ajt.com", "이관리"));
        inquiryService.create(login(author), request(assignee.getId(), List.of()));
        inquiryService.create(login(author), request(otherAdmin.getId(), List.of()));

        InquiryListResponse response = inquiryService.findInquiries(
                login(assignee), null, null, null, null, null, null, null, null, null);

        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.items().getFirst().assignee().name()).isEqualTo("김관리");
    }

    @Test
    @DisplayName("문의 목록은 status로 미처리(pending)·처리완료(done)를 구분해 필터링한다(S15P11B106-226)")
    void findInquiriesFiltersByStatus() {
        // 담당자가 같은 두 문의 중 하나만 답변해 DONE으로 만든 뒤, status 필터가 각각만 조회하는지 확인한다.
        // 권한 스코프(담당자=본인 담당) 위에 status 필터가 함께 적용된다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        inquiryService.create(login(author), request(assignee.getId(), List.of()));
        InquiryResponse done = inquiryService.create(login(author), request(assignee.getId(), List.of()));
        inquiryService.upsertAnswer(login(assignee), Long.parseLong(done.inquiryId()), "규정에 따라 처리했습니다");

        // status 없이 조회하면 담당 문의 둘 다 보인다.
        assertThat(inquiryService.findInquiries(
                login(assignee), null, null, null, null, null, null, null, null, null).totalCount())
                .isEqualTo(2);

        // 미처리(pending)만 — 답변하지 않은 문의 1건.
        InquiryListResponse pendingOnly = inquiryService.findInquiries(
                login(assignee), null, null, "pending", null, null, null, null, null, null);
        assertThat(pendingOnly.totalCount()).isEqualTo(1);
        assertThat(pendingOnly.items().getFirst().status()).isEqualTo("pending");

        // 처리완료(done)만 — 답변한 문의 1건.
        InquiryListResponse doneOnly = inquiryService.findInquiries(
                login(assignee), null, null, "done", null, null, null, null, null, null);
        assertThat(doneOnly.totalCount()).isEqualTo(1);
        assertThat(doneOnly.items().getFirst().status()).isEqualTo("done");
    }

    @Test
    @DisplayName("문의 목록은 keyword로 제목과 요청자 이름을 부분 일치 검색한다")
    void findInquiriesSearchesByTitleAndAuthorName() {
        // 요청자가 다른 두 문의를 담당자 한 명에게 등록해 두고, 제목·요청자 이름 각각으로 검색되는지 확인한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member other = memberRepository.save(approvedEmployee(department, "other@ajt.com", "박사원", "AJT-2026-0002"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        inquiryService.create(login(author), request(assignee.getId(), "연차 문의", List.of()));
        inquiryService.create(login(other), request(assignee.getId(), "출장비 정산 문의", List.of()));

        // 제목 부분 일치 — '출장비 정산 문의' 1건.
        InquiryListResponse byTitle = inquiryService.findInquiries(
                login(assignee), null, null, null, null, null, null, null, null, "출장비");
        assertThat(byTitle.totalCount()).isEqualTo(1);
        assertThat(byTitle.items().getFirst().title()).isEqualTo("출장비 정산 문의");

        // 요청자 이름 부분 일치 — 홍길동이 등록한 1건.
        InquiryListResponse byAuthor = inquiryService.findInquiries(
                login(assignee), null, null, null, null, null, null, null, null, "홍길");
        assertThat(byAuthor.totalCount()).isEqualTo(1);
        assertThat(byAuthor.items().getFirst().author().name()).isEqualTo("홍길동");

        // 어느 쪽에도 없는 검색어는 0건이다.
        assertThat(inquiryService.findInquiries(
                login(assignee), null, null, null, null, null, null, null, null, "없는검색어").totalCount())
                .isZero();
    }

    @Test
    @DisplayName("문의 상세 조회는 권한이 없는 사용자에게 404로 존재를 숨긴다")
    void getInquiryHidesFromUnauthorizedUser() {
        // 작성자도 담당자도 아닌 제3자는 문의 존재 자체를 알 수 없도록 404가 나야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        Member stranger = memberRepository.save(approvedEmployee(department, "other@ajt.com", "박사원", "AJT-2026-0002"));
        InquiryResponse created = inquiryService.create(login(author), request(assignee.getId(), List.of()));
        long inquiryId = Long.parseLong(created.inquiryId());

        assertThatThrownBy(() -> inquiryService.getInquiry(login(stranger), inquiryId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INQUIRY_NOT_FOUND);
    }

    @Test
    @DisplayName("문의 삭제는 작성자가 요청하면 문의와 답변을 함께 하드 삭제한다")
    void deleteInquiryRemovesInquiryAndAnswer() {
        // 답변까지 달린 문의를 작성자가 삭제하면 문의와 답변이 모두 사라져야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        InquiryResponse created = inquiryService.create(login(author), request(assignee.getId(), List.of()));
        long inquiryId = Long.parseLong(created.inquiryId());
        inquiryService.upsertAnswer(login(assignee), inquiryId, "답변입니다");

        inquiryService.deleteInquiry(login(author), inquiryId);

        assertThat(inquiryRepository.findById(inquiryId)).isEmpty();
        assertThat(inquiryReplyRepository.findByInquiryId(inquiryId)).isEmpty();
    }

    @Test
    @DisplayName("문의 삭제는 작성자도 담당자도 아니면 403으로 거부한다")
    void deleteInquiryRejectsStranger() {
        // 권한 없는 제3자가 삭제를 시도하면 막혀야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        Member stranger = memberRepository.save(approvedEmployee(department, "other@ajt.com", "박사원", "AJT-2026-0002"));
        InquiryResponse created = inquiryService.create(login(author), request(assignee.getId(), List.of()));
        long inquiryId = Long.parseLong(created.inquiryId());

        assertThatThrownBy(() -> inquiryService.deleteInquiry(login(stranger), inquiryId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INQUIRY_FORBIDDEN);
    }

    @Test
    @DisplayName("답변 작성은 지정 담당자만 할 수 있고 문의 상태를 done으로 바꾼다")
    void upsertAnswerByAssigneeMarksDone() {
        // 담당자가 답변을 작성하면 문의가 답변 완료 상태로 바뀐다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        InquiryResponse created = inquiryService.create(login(author), request(assignee.getId(), List.of()));
        long inquiryId = Long.parseLong(created.inquiryId());

        InquiryAnswerResponse answer = inquiryService.upsertAnswer(login(assignee), inquiryId, "규정에 따라 사용 가능합니다");

        assertThat(answer.content()).isEqualTo("규정에 따라 사용 가능합니다");
        assertThat(answer.adminName()).isEqualTo("김관리");
        assertThat(inquiryRepository.findById(inquiryId).orElseThrow().getStatus()).isEqualTo(InquiryStatus.DONE);
    }

    @Test
    @DisplayName("이미 답변이 있으면 같은 담당자의 재등록은 충돌 없이 내용을 교체한다(정상 수정 흐름)")
    void upsertAnswerReplacesExistingAnswer() {
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        InquiryResponse created = inquiryService.create(login(author), request(assignee.getId(), List.of()));
        long inquiryId = Long.parseLong(created.inquiryId());
        inquiryService.upsertAnswer(login(assignee), inquiryId, "첫 답변");

        InquiryAnswerResponse updated = inquiryService.upsertAnswer(login(assignee), inquiryId, "수정된 답변");

        assertThat(updated.content()).isEqualTo("수정된 답변");
        assertThat(inquiryRepository.findById(inquiryId).orElseThrow().getStatus()).isEqualTo(InquiryStatus.DONE);
    }

    @Test
    @DisplayName("답변 작성은 지정 담당자가 아니면 403으로 거부한다")
    void upsertAnswerRejectsNonAssignee() {
        // 다른 관리자가 답변을 시도하면 담당자가 아니므로 막혀야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        Member otherAdmin = memberRepository.save(approvedAdmin(department, "admin2@ajt.com", "이관리"));
        InquiryResponse created = inquiryService.create(login(author), request(assignee.getId(), List.of()));
        long inquiryId = Long.parseLong(created.inquiryId());

        assertThatThrownBy(() -> inquiryService.upsertAnswer(login(otherAdmin), inquiryId, "제가 답변합니다"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INQUIRY_FORBIDDEN);
    }

    @Test
    @DisplayName("답변 삭제는 문의를 다시 pending 상태로 되돌린다")
    void deleteAnswerResetsStatusToPending() {
        // 답변을 삭제하면 문의가 답변 대기 상태로 복귀해야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        InquiryResponse created = inquiryService.create(login(author), request(assignee.getId(), List.of()));
        long inquiryId = Long.parseLong(created.inquiryId());
        inquiryService.upsertAnswer(login(assignee), inquiryId, "답변입니다");

        inquiryService.deleteAnswer(login(assignee), inquiryId);

        assertThat(inquiryReplyRepository.findByInquiryId(inquiryId)).isEmpty();
        assertThat(inquiryRepository.findById(inquiryId).orElseThrow().getStatus()).isEqualTo(InquiryStatus.PENDING);
    }

    @Test
    @DisplayName("첨부 이미지 다운로드는 권한 있는 사용자에게 저장된 파일 리소스를 반환한다")
    void downloadAttachmentReturnsResourceForAuthorizedUser() {
        // 작성자가 자신이 올린 첨부 이미지를 내려받을 수 있어야 한다.
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        MockMultipartFile image = new MockMultipartFile("attachments", "photo.png", "image/png", new byte[]{1, 2, 3});
        InquiryResponse created = inquiryService.create(login(author), request(assignee.getId(), List.of(image)));
        long inquiryId = Long.parseLong(created.inquiryId());
        String attachmentId = created.attachments().getFirst().attachmentId();

        InquiryAttachmentDownload download = inquiryService.downloadAttachment(login(author), inquiryId, attachmentId);

        assertThat(download.mimeType()).isEqualTo("image/png");
        assertThat(download.fileName()).isEqualTo("photo.png");
        assertThat(download.resource().exists()).isTrue();
    }

    @Test
    @DisplayName("문의 목록은 최고관리자에게 담당자가 아니어도 전체 문의를 보여준다(S15P11B106-146)")
    void findInquiriesShowsAllForSuperAdmin() {
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        Member otherAdmin = memberRepository.save(approvedAdmin(department, "admin2@ajt.com", "이관리"));
        Member superAdmin = memberRepository.save(approvedAdmin(department, SUPER_ADMIN_EMAIL, "최고관리자"));
        inquiryService.create(login(author), request(assignee.getId(), List.of()));
        inquiryService.create(login(author), request(otherAdmin.getId(), List.of()));

        // 최고관리자는 어느 문의의 담당자도 아니지만 전체(2건)를 조회한다.
        InquiryListResponse response = inquiryService.findInquiries(
                login(superAdmin), null, null, null, null, null, null, null, null, null);

        assertThat(response.totalCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("문의 상세는 최고관리자에게 작성자·담당자가 아니어도 조회를 허용한다(S15P11B106-146)")
    void getInquiryAllowsSuperAdmin() {
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        Member superAdmin = memberRepository.save(approvedAdmin(department, SUPER_ADMIN_EMAIL, "최고관리자"));
        InquiryResponse created = inquiryService.create(login(author), request(assignee.getId(), List.of()));
        long inquiryId = Long.parseLong(created.inquiryId());

        InquiryResponse detail = inquiryService.getInquiry(login(superAdmin), inquiryId);

        assertThat(detail.inquiryId()).isEqualTo(String.valueOf(inquiryId));
    }

    @Test
    @DisplayName("답변은 최고관리자가 담당자가 아니어도 작성할 수 있고 답변자로 최고관리자가 기록된다(S15P11B106-146)")
    void upsertAnswerAllowsSuperAdmin() {
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        Member superAdmin = memberRepository.save(approvedAdmin(department, SUPER_ADMIN_EMAIL, "최고관리자"));
        InquiryResponse created = inquiryService.create(login(author), request(assignee.getId(), List.of()));
        long inquiryId = Long.parseLong(created.inquiryId());

        InquiryAnswerResponse answer = inquiryService.upsertAnswer(
                login(superAdmin), inquiryId, "최고관리자가 직접 답변합니다");

        assertThat(answer.content()).isEqualTo("최고관리자가 직접 답변합니다");
        // 담당자(김관리)가 아니라 실제 답변자(최고관리자)로 기록된다.
        assertThat(answer.adminId()).isEqualTo(String.valueOf(superAdmin.getId()));
        assertThat(answer.adminName()).isEqualTo("최고관리자");
        assertThat(inquiryRepository.findById(inquiryId).orElseThrow().getStatus()).isEqualTo(InquiryStatus.DONE);
    }

    @Test
    @DisplayName("사원은 문의에 답변할 수 없다(403)(S15P11B106-146)")
    void upsertAnswerRejectsEmployee() {
        Department department = departmentRepository.save(new Department("인사부"));
        Member author = memberRepository.save(approvedEmployee(department, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        Member assignee = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        InquiryResponse created = inquiryService.create(login(author), request(assignee.getId(), List.of()));
        long inquiryId = Long.parseLong(created.inquiryId());

        assertThatThrownBy(() -> inquiryService.upsertAnswer(login(author), inquiryId, "제가 답변합니다"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INQUIRY_FORBIDDEN);
    }

    @Test
    @DisplayName("담당자 후보 조회는 최고관리자를 제외한다(S15P11B106-146)")
    void findAssigneesExcludesSuperAdmin() {
        Department department = departmentRepository.save(new Department("인사부"));
        Member admin = memberRepository.save(approvedAdmin(department, "admin@ajt.com", "김관리"));
        memberRepository.save(approvedAdmin(department, SUPER_ADMIN_EMAIL, "최고관리자"));

        InquiryAssigneeListResponse response = inquiryService.findAssignees(login(admin), null);

        assertThat(response.items()).extracting("name")
                .contains("김관리")
                .doesNotContain("최고관리자");
    }

    // 로그인 사용자 정보를 만드는 헬퍼입니다.
    private AuthenticatedMember login(Member member) {
        return new AuthenticatedMember(member.getId(), member.getEmail(), member.getRole());
    }

    // 문의 등록 요청을 만드는 헬퍼입니다. 제목·내용·우선순위는 고정하고 담당자와 첨부만 바꿔 씁니다.
    private InquiryCreateRequest request(long assigneeId, List<MultipartFile> attachments) {
        return request(assigneeId, "연차 문의", attachments);
    }

    private InquiryCreateRequest request(long assigneeId, String title, List<MultipartFile> attachments) {
        return InquiryCreateRequest.of(assigneeId, title, "연차 사용 기준이 궁금합니다.", "normal", attachments);
    }

    private Member approvedAdmin(Department department, String email, String name) {
        return Member.approved(department, email, name, passwordEncoder.encode("password123!"),
                "AJT-2026-" + Math.abs(email.hashCode() % 10000), Role.ADMIN);
    }

    private Member approvedEmployee(Department department, String email, String name, String employeeNo) {
        return Member.approvedEmployee(department, email, name, passwordEncoder.encode("password123!"), employeeNo);
    }
}
