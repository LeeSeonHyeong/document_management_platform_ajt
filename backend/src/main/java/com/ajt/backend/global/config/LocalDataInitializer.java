package com.ajt.backend.global.config;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.inquiry.Inquiry;
import com.ajt.backend.domain.inquiry.InquiryPriority;
import com.ajt.backend.domain.inquiry.InquiryReply;
import com.ajt.backend.domain.inquiry.InquiryReplyRepository;
import com.ajt.backend.domain.inquiry.InquiryRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.multipart.MultipartFile;

/**
 * local H2 개발/시연용 기본 데이터를 넣습니다.
 * 실제 배포 환경에서는 실행되지 않고(local 프로필 + ajt.local-data.enabled=true에서만),
 * 팀이 동일한 부서·회원·카테고리로 API를 확인·소통할 수 있게 코드로 관리합니다.
 *
 * <p>각 항목은 이미 있으면 건너뛰므로 재실행해도 중복되지 않습니다(idempotent).
 * 비밀번호는 모두 {@code password123!} 입니다.
 */
@Configuration
@Profile("local")
@ConditionalOnProperty(prefix = "ajt.local-data", name = "enabled", havingValue = "true")
public class LocalDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(LocalDataInitializer.class);
    private static final String DEFAULT_PASSWORD = "password123!";
    // 나중에 실제 데이터로 교체할 때 식별·삭제하기 쉽도록 샘플 문서·위키에 붙이는 접두사.
    private static final String SAMPLE_PREFIX = "[샘플] ";

    @Bean
    CommandLineRunner seedLocalData(
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            WikiScopeRepository wikiScopeRepository,
            DocumentCategoryRepository documentCategoryRepository,
            DocumentRepository documentRepository,
            DocumentFileStorage documentFileStorage,
            WikiRepository wikiRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiFileStorage wikiFileStorage,
            InquiryRepository inquiryRepository,
            InquiryReplyRepository inquiryReplyRepository,
            ScheduleRepository scheduleRepository
    ) {
        return args -> {
            // 1) 부서
            Department dev = findOrCreateDepartment(departmentRepository, "개발부");
            Department planning = findOrCreateDepartment(departmentRepository, "기획부");
            Department design = findOrCreateDepartment(departmentRepository, "디자인부");
            Department hr = findOrCreateDepartment(departmentRepository, "인사부");

            // 2) 승인된 회원 (부서별 관리자 1 + 사원 1). admin@ajt.com / employee@ajt.com은 기존 계정 유지.
            Member devAdmin = createAdmin(memberRepository, passwordEncoder, dev, "admin@ajt.com", "관리자", "AJT-2026-0002");
            Member devEmployee = createEmployee(memberRepository, passwordEncoder, dev, "employee@ajt.com", "홍길동", "AJT-2026-0001");
            Member planningAdmin = createAdmin(memberRepository, passwordEncoder, planning, "planning.admin@ajt.com", "김기획", "AJT-2026-0003");
            createEmployee(memberRepository, passwordEncoder, planning, "planning.emp@ajt.com", "이기획", "AJT-2026-0004");
            Member designAdmin = createAdmin(memberRepository, passwordEncoder, design, "design.admin@ajt.com", "박디자인", "AJT-2026-0005");
            createEmployee(memberRepository, passwordEncoder, design, "design.emp@ajt.com", "최디자인", "AJT-2026-0006");
            Member hrAdmin = createAdmin(memberRepository, passwordEncoder, hr, "hr.admin@ajt.com", "정인사", "AJT-2026-0007");
            createEmployee(memberRepository, passwordEncoder, hr, "hr.emp@ajt.com", "강인사", "AJT-2026-0008");

            // 3) 부서장 지정 (각 부서 admin을 자기 부서의 관리자로 — 부서장 표시 테스트용)
            assignManager(departmentRepository, dev, devAdmin);
            assignManager(departmentRepository, planning, planningAdmin);
            assignManager(departmentRepository, design, designAdmin);
            assignManager(departmentRepository, hr, hrAdmin);

            // 4) 가입 신청 — 승인 대기 + 거절됨 (가입요청 목록의 상태별 확인용)
            createPendingSignup(memberRepository, passwordEncoder, dev, "pending1@ajt.com", "신입일");
            createPendingSignup(memberRepository, passwordEncoder, planning, "pending2@ajt.com", "신입이");
            createRejectedSignup(memberRepository, passwordEncoder, design, "rejected1@ajt.com", "거절일");
            createRejectedSignup(memberRepository, passwordEncoder, hr, "rejected2@ajt.com", "거절이");

            // 5) Wiki 공간(scope) + 문서 카테고리
            seedScopeWithCategories(wikiScopeRepository, documentCategoryRepository,
                    WikiScope.all(), List.of("사규", "복지제도", "공지사항"));
            seedDepartmentScope(wikiScopeRepository, documentCategoryRepository, dev, List.of("개발 가이드", "API 문서"));
            seedDepartmentScope(wikiScopeRepository, documentCategoryRepository, planning, List.of("기획 문서", "회의록"));
            seedDepartmentScope(wikiScopeRepository, documentCategoryRepository, design, List.of("디자인 가이드", "브랜드"));
            seedDepartmentScope(wikiScopeRepository, documentCategoryRepository, hr, List.of("인사 규정", "채용 공고"));

            // 6~7) 시연용 샘플 문서·위키 ([샘플] 접두사로 표시 → 나중에 실제 데이터로 교체 시 식별·삭제 용이).
            //      파일 I/O가 얽혀 있어 실패해도 로컬 기동은 계속되도록 방어적으로 감싼다.
            try {
                List<DocumentCategory> allCategories =
                        documentCategoryRepository.findAllByScopeKeyOrderByNameAsc("ALL");
                if (!allCategories.isEmpty()) {
                    seedSampleDocuments(documentRepository, documentFileStorage,
                            devAdmin.getId(), "ALL", allCategories.get(0).id());
                }
                seedSampleWikis(wikiRepository, wikiCategoryRepository, wikiFileStorage, "ALL");
            } catch (Exception exception) {
                log.warn("샘플 문서·위키 시드 실패(무시하고 기동 계속): {}", exception.getMessage(), exception);
            }

            // 8) 시연용 샘플 문의 (문의 관리 목록·상세·답변 상태 확인용)
            seedSampleInquiries(inquiryRepository, inquiryReplyRepository, devEmployee, devAdmin);

            // 9) 시연용 샘플 일정 (일정 관리 달력·공개범위·검수대기 확인용)
            seedSampleSchedules(scheduleRepository, devAdmin.getId(), devEmployee.getId(), dev.getId());
        };
    }

    private Department findOrCreateDepartment(DepartmentRepository departmentRepository, String name) {
        return departmentRepository.findAllByOrderByNameAsc()
                .stream()
                .filter(department -> name.equals(department.getName()))
                .findFirst()
                .orElseGet(() -> departmentRepository.save(new Department(name)));
    }

    private Member createAdmin(
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            Department department,
            String email,
            String name,
            String employeeNo
    ) {
        return memberRepository.findByEmail(email)
                .orElseGet(() -> memberRepository.save(Member.approved(
                        department, email, name, passwordEncoder.encode(DEFAULT_PASSWORD), employeeNo, Role.ADMIN)));
    }

    private Member createEmployee(
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            Department department,
            String email,
            String name,
            String employeeNo
    ) {
        return memberRepository.findByEmail(email)
                .orElseGet(() -> memberRepository.save(Member.approvedEmployee(
                        department, email, name, passwordEncoder.encode(DEFAULT_PASSWORD), employeeNo)));
    }

    private void createPendingSignup(
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            Department department,
            String email,
            String name
    ) {
        memberRepository.findByEmail(email)
                .orElseGet(() -> memberRepository.save(Member.signup(
                        department, email, name, passwordEncoder.encode(DEFAULT_PASSWORD))));
    }

    private void createRejectedSignup(
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            Department department,
            String email,
            String name
    ) {
        if (memberRepository.findByEmail(email).isPresent()) {
            return;
        }
        Member member = Member.signup(department, email, name, passwordEncoder.encode(DEFAULT_PASSWORD));
        member.rejectSignup();
        memberRepository.save(member);
    }

    private void assignManager(DepartmentRepository departmentRepository, Department department, Member admin) {
        if (department.getManager() == null) {
            department.assignManager(admin);
            departmentRepository.save(department);
        }
    }

    private void seedDepartmentScope(
            WikiScopeRepository wikiScopeRepository,
            DocumentCategoryRepository documentCategoryRepository,
            Department department,
            List<String> categoryNames
    ) {
        seedScopeWithCategories(wikiScopeRepository, documentCategoryRepository,
                WikiScope.department(List.of(department.getId())), categoryNames);
    }

    private void seedScopeWithCategories(
            WikiScopeRepository wikiScopeRepository,
            DocumentCategoryRepository documentCategoryRepository,
            WikiScope scope,
            List<String> categoryNames
    ) {
        String scopeKey = scope.scopeKey();
        if (!wikiScopeRepository.existsById(scopeKey)) {
            wikiScopeRepository.save(scope);
        }
        for (String categoryName : categoryNames) {
            if (!documentCategoryRepository.existsByScopeKeyAndName(scopeKey, categoryName)) {
                documentCategoryRepository.save(DocumentCategory.create(scopeKey, categoryName, null));
            }
        }
    }

    // ---- 시연용 샘플 문서 ----

    private void seedSampleDocuments(
            DocumentRepository documentRepository,
            DocumentFileStorage documentFileStorage,
            long uploaderId,
            String scopeKey,
            long documentCategoryId
    ) throws IOException {
        createSampleDocument(documentRepository, documentFileStorage, uploaderId, scopeKey, documentCategoryId,
                SAMPLE_PREFIX + "취업규칙.md", "text/markdown",
                "# 취업규칙\n\n근무시간, 휴가, 복무 규정을 담은 시연용 샘플 원본문서입니다.", true);
        createSampleDocument(documentRepository, documentFileStorage, uploaderId, scopeKey, documentCategoryId,
                SAMPLE_PREFIX + "사내 복지제도.md", "text/markdown",
                "# 사내 복지제도\n\n식대, 교육비, 경조사 지원 등을 담은 시연용 샘플 원본문서입니다.", true);
        createSampleDocument(documentRepository, documentFileStorage, uploaderId, scopeKey, documentCategoryId,
                SAMPLE_PREFIX + "처리실패문서.md", "text/markdown",
                "# 처리 실패 예시\n\nAI 서버 미연동으로 변환에 실패한 상태를 보여주는 시연용 샘플입니다.", false);
    }

    // completed=true면 COMPLETED, false면 FAILED 상태의 샘플 문서를 만든다.
    private void createSampleDocument(
            DocumentRepository documentRepository,
            DocumentFileStorage documentFileStorage,
            long uploaderId,
            String scopeKey,
            long documentCategoryId,
            String fileName,
            String mimeType,
            String content,
            boolean completed
    ) throws IOException {
        boolean exists = documentRepository.findByScopeKey(scopeKey).stream()
                .anyMatch(document -> fileName.equals(document.originalFileName()));
        if (exists) {
            return;
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Document document = documentRepository.save(Document.uploaded(
                uploaderId, documentCategoryId, scopeKey, fileName, "pending", mimeType, bytes.length));
        String originalPath = documentFileStorage.storeOriginal(
                scopeKey, document.id(), new InMemoryMultipartFile(fileName, mimeType, bytes));
        document.changeOriginalPath(originalPath);

        document.startParsing();
        if (completed) {
            String parsedPath = documentFileStorage.storeParsedMarkdown(scopeKey, document.id(), content);
            document.completeParsing(parsedPath);
            document.completeProcessing(List.of());
        } else {
            document.failParsing("샘플: AI 서버 미연동으로 변환에 실패한 예시입니다.");
        }
        documentRepository.save(document);
    }

    // ---- 시연용 샘플 위키 ----

    private void seedSampleWikis(
            WikiRepository wikiRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiFileStorage wikiFileStorage,
            String scopeKey
    ) throws IOException {
        WikiCategory category = wikiCategoryRepository.findAllByScopeKeyOrderByNameAsc(scopeKey).stream()
                .filter(wikiCategory -> "샘플".equals(wikiCategory.name()))
                .findFirst()
                .orElseGet(() -> wikiCategoryRepository.save(
                        WikiCategory.create(scopeKey, "샘플", "시연용 샘플 위키 분류")));

        createSampleWiki(wikiRepository, wikiFileStorage, scopeKey, category.id(),
                SAMPLE_PREFIX + "취업규칙 위키",
                "# 취업규칙\n\n## 근무시간\n평일 09:00~18:00.\n\n## 휴가\n연차·반차 규정 요약.\n",
                "근무·휴가·복무 규정 요약(시연용)");
        createSampleWiki(wikiRepository, wikiFileStorage, scopeKey, category.id(),
                SAMPLE_PREFIX + "복지제도 위키",
                "# 사내 복지제도\n\n- 식대 지원\n- 교육비 지원\n- 경조사 지원\n",
                "사내 복지 제도 안내(시연용)");
    }

    private void createSampleWiki(
            WikiRepository wikiRepository,
            WikiFileStorage wikiFileStorage,
            String scopeKey,
            long wikiCategoryId,
            String title,
            String content,
            String summary
    ) throws IOException {
        boolean exists = wikiRepository.findAllByScopeKey(scopeKey).stream()
                .anyMatch(wiki -> title.equals(wiki.title()));
        if (exists) {
            return;
        }

        Wiki wiki = wikiRepository.save(Wiki.create(scopeKey, wikiCategoryId, title));
        String wikiPath = wiki.assignStoragePath();
        wikiFileStorage.storeWikiMarkdown(wikiPath, content);
        wiki.changeContentHash(sha256(content));
        wiki.changeSummary(summary);
        wikiRepository.save(wiki);
    }

    // ---- 시연용 샘플 문의 ----

    private void seedSampleInquiries(
            InquiryRepository inquiryRepository,
            InquiryReplyRepository inquiryReplyRepository,
            Member author,
            Member assignee
    ) {
        if (inquiryRepository.count() > 0) {
            return;
        }
        createInquiry(inquiryRepository, inquiryReplyRepository, author, assignee,
                SAMPLE_PREFIX + "연차 사용 문의", "연차를 반차 단위로 나눠 쓸 수 있나요?",
                InquiryPriority.NORMAL, "네, 반차(오전/오후) 단위로 사용 가능합니다.");
        createInquiry(inquiryRepository, inquiryReplyRepository, author, assignee,
                SAMPLE_PREFIX + "개발 노트북 사양 문의", "신규 입사자 개발용 노트북 사양이 궁금합니다.",
                InquiryPriority.HIGH, null);
        createInquiry(inquiryRepository, inquiryReplyRepository, author, assignee,
                SAMPLE_PREFIX + "교육비 지원 범위", "온라인 강의도 교육비 지원 대상인가요?",
                InquiryPriority.LOW, null);
    }

    // answer가 있으면 답변 등록 + 처리 완료(DONE), 없으면 답변 대기(PENDING) 상태로 둔다.
    private void createInquiry(
            InquiryRepository inquiryRepository,
            InquiryReplyRepository inquiryReplyRepository,
            Member author,
            Member assignee,
            String title,
            String content,
            InquiryPriority priority,
            String answer
    ) {
        Inquiry inquiry = inquiryRepository.save(Inquiry.create(author, assignee, title, content, priority));
        if (answer != null) {
            inquiryReplyRepository.save(InquiryReply.create(inquiry.getId(), assignee, answer));
            inquiry.markAnswered();
            inquiryRepository.save(inquiry);
        }
    }

    // ---- 시연용 샘플 일정 ----

    private void seedSampleSchedules(
            ScheduleRepository scheduleRepository,
            long adminId,
            long employeeId,
            long departmentId
    ) {
        if (scheduleRepository.count() > 0) {
            return;
        }
        Instant base = Instant.now();

        // 전사(ALL) 승인 일정
        scheduleRepository.save(Schedule.create(adminId, SAMPLE_PREFIX + "전사 워크샵",
                "상반기 전사 워크샵입니다.", "전 직원", "본사 대강당",
                ScheduleVisibility.ALL, base.plus(Duration.ofDays(2)), base.plus(Duration.ofDays(2)).plus(Duration.ofHours(3))));

        // 부서(DEPARTMENT) 승인 일정 — 부서 연결
        Schedule departmentSchedule = Schedule.create(adminId, SAMPLE_PREFIX + "개발부 스프린트 회의",
                "스프린트 계획 회의입니다.", "개발부", "회의실 A",
                ScheduleVisibility.DEPARTMENT, base.plus(Duration.ofDays(1)), base.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)));
        departmentSchedule.replaceDepartments(List.of(departmentId));
        scheduleRepository.save(departmentSchedule);

        // 개인(PERSONAL) 일정 — 작성자 본인만
        scheduleRepository.save(Schedule.create(employeeId, SAMPLE_PREFIX + "개인 연차",
                "개인 연차 사용입니다.", "본인", null,
                ScheduleVisibility.PERSONAL, base.plus(Duration.ofDays(3)), base.plus(Duration.ofDays(3)).plus(Duration.ofHours(8))));

        // 검수 대기(DRAFT) 일정 — 일정 관리 화면의 "승인 대기" 확인용
        scheduleRepository.save(Schedule.draft(adminId, SAMPLE_PREFIX + "AI 추출 일정(검수 대기)",
                "원본문서에서 추출된 검수 대기 일정 예시입니다.", "전 직원", "미정",
                ScheduleVisibility.ALL, base.plus(Duration.ofDays(5)), base.plus(Duration.ofDays(5)).plus(Duration.ofHours(2))));
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 미지원", exception);
        }
    }

    // 시드에서 파일 저장소(MultipartFile 기반 API)를 재사용하기 위한 최소 구현.
    private record InMemoryMultipartFile(String fileName, String contentType, byte[] content)
            implements MultipartFile {

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return fileName;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File destination) throws IOException {
            Files.write(destination.toPath(), content);
        }
    }
}
