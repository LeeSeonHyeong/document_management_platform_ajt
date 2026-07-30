package com.ajt.backend.global.config;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

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

    private static final String DEFAULT_PASSWORD = "password123!";

    @Bean
    CommandLineRunner seedLocalData(
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            WikiScopeRepository wikiScopeRepository,
            DocumentCategoryRepository documentCategoryRepository
    ) {
        return args -> {
            // 1) 부서
            Department dev = findOrCreateDepartment(departmentRepository, "개발부");
            Department planning = findOrCreateDepartment(departmentRepository, "기획부");
            Department design = findOrCreateDepartment(departmentRepository, "디자인부");
            Department hr = findOrCreateDepartment(departmentRepository, "인사부");

            // 2) 승인된 회원 (부서별 관리자 1 + 사원 1). admin@ajt.com / employee@ajt.com은 기존 계정 유지.
            Member devAdmin = createAdmin(memberRepository, passwordEncoder, dev, "admin@ajt.com", "관리자", "AJT-2026-0002");
            createEmployee(memberRepository, passwordEncoder, dev, "employee@ajt.com", "홍길동", "AJT-2026-0001");
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

            // 4) 가입 승인 대기 신청 (승인/거절 흐름 테스트용)
            createPendingSignup(memberRepository, passwordEncoder, dev, "pending1@ajt.com", "신입일");
            createPendingSignup(memberRepository, passwordEncoder, planning, "pending2@ajt.com", "신입이");

            // 5) Wiki 공간(scope) + 문서 카테고리
            seedScopeWithCategories(wikiScopeRepository, documentCategoryRepository,
                    WikiScope.all(), List.of("사규", "복지제도", "공지사항"));
            seedDepartmentScope(wikiScopeRepository, documentCategoryRepository, dev, List.of("개발 가이드", "API 문서"));
            seedDepartmentScope(wikiScopeRepository, documentCategoryRepository, planning, List.of("기획 문서", "회의록"));
            seedDepartmentScope(wikiScopeRepository, documentCategoryRepository, design, List.of("디자인 가이드", "브랜드"));
            seedDepartmentScope(wikiScopeRepository, documentCategoryRepository, hr, List.of("인사 규정", "채용 공고"));
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
}
