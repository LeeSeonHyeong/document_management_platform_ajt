package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.document.api.DocumentCategoryCreateRequest;
import com.ajt.backend.domain.document.api.DocumentCategoryListResponse;
import com.ajt.backend.domain.document.api.DocumentCategoryResponse;
import com.ajt.backend.domain.document.api.DocumentCategoryUpdateRequest;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false",
        "ajt.super-admin.email=super@ajt.com"
})
@Transactional
@DisplayName("문서 카테고리 서비스")
class DocumentCategoryServiceTest {

    private final DocumentCategoryService documentCategoryService;
    private final DocumentCategoryRepository documentCategoryRepository;
    private final DocumentRepository documentRepository;
    private final WikiScopeRepository wikiScopeRepository;
    private final MemberRepository memberRepository;
    private final DepartmentRepository departmentRepository;

    private Department dev;
    private Department other;
    private long superAdminId;
    private long deptManagerId;
    private long employeeId;
    private String devScope;    // 부서관리자 담당 부서 단일 scope "D{dev}"
    private String otherScope;  // 타부서 "D{other}"
    private String multiScope;  // 복수부서 "D{dev}-D{other}"

    @Autowired
    DocumentCategoryServiceTest(
            DocumentCategoryService documentCategoryService,
            DocumentCategoryRepository documentCategoryRepository,
            DocumentRepository documentRepository,
            WikiScopeRepository wikiScopeRepository,
            MemberRepository memberRepository,
            DepartmentRepository departmentRepository
    ) {
        this.documentCategoryService = documentCategoryService;
        this.documentCategoryRepository = documentCategoryRepository;
        this.documentRepository = documentRepository;
        this.wikiScopeRepository = wikiScopeRepository;
        this.memberRepository = memberRepository;
        this.departmentRepository = departmentRepository;
    }

    @BeforeEach
    void seed() {
        Department home = departmentRepository.save(new Department("본사"));
        dev = departmentRepository.save(new Department("개발부"));
        other = departmentRepository.save(new Department("기획부"));
        devScope = WikiScope.department(List.of(dev.getId())).scopeKey();
        otherScope = WikiScope.department(List.of(other.getId())).scopeKey();
        multiScope = WikiScope.department(List.of(dev.getId(), other.getId())).scopeKey();

        superAdminId = memberRepository.save(Member.approved(
                home, "super@ajt.com", "최고관리자", "hash", "AJT-2026-9999", Role.ADMIN)).getId();
        Member manager = memberRepository.save(Member.approved(
                dev, "mgr@ajt.com", "부서관리자", "hash", "AJT-2026-0002", Role.ADMIN));
        dev.assignManager(manager);
        departmentRepository.save(dev);
        deptManagerId = manager.getId();
        employeeId = memberRepository.save(Member.approvedEmployee(
                dev, "employee@ajt.com", "사원", "hash", "AJT-2026-0001")).getId();
    }

    // ===== 기존 동작(최고관리자·사원) =====

    @Test
    @DisplayName("목록 조회는 같은 Wiki 공간의 카테고리만 이름순으로 반환한다")
    void findCategoriesReturnsScopeCategoriesByName() {
        wikiScopeRepository.save(WikiScope.department(List.of(dev.getId())));
        documentCategoryRepository.save(DocumentCategory.create(devScope, "휴가", null));
        documentCategoryRepository.save(DocumentCategory.create(devScope, "근태", null));
        wikiScopeRepository.save(WikiScope.all());
        documentCategoryRepository.save(DocumentCategory.create("ALL", "공통", null));

        DocumentCategoryListResponse response = documentCategoryService.findCategories(superAdmin(), devScope);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).name()).isEqualTo("근태");
        assertThat(response.items().get(1).name()).isEqualTo("휴가");
    }

    @Test
    @DisplayName("목록 조회는 존재하지 않는 Wiki 공간이면 404 예외를 발생시킨다")
    void findCategoriesRejectsMissingScope() {
        assertThatThrownBy(() -> documentCategoryService.findCategories(superAdmin(), "D999"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_SCOPE_NOT_FOUND);
    }

    @Test
    @DisplayName("생성은 최고관리자가 카테고리를 저장하고 없는 Wiki 공간은 함께 준비한다")
    void createCategoryCreatesCategoryAndScope() {
        DocumentCategoryResponse response = documentCategoryService.createCategory(
                superAdmin(),
                new DocumentCategoryCreateRequest(multiScope, " 사내 규정 ", "공통 규정")
        );

        assertThat(response.scopeKey()).isEqualTo(multiScope);
        assertThat(response.name()).isEqualTo("사내 규정");
        assertThat(wikiScopeRepository.existsById(multiScope)).isTrue();
    }

    @Test
    @DisplayName("생성은 일반 사용자를 403 예외로 거절한다")
    void createCategoryRejectsEmployee() {
        assertThatThrownBy(() -> documentCategoryService.createCategory(
                employee(),
                new DocumentCategoryCreateRequest("ALL", "공통 규정", null)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
    }

    @Test
    @DisplayName("생성은 같은 Wiki 공간의 중복 카테고리명을 거절한다")
    void createCategoryRejectsDuplicateName() {
        wikiScopeRepository.save(WikiScope.all());
        documentCategoryRepository.save(DocumentCategory.create("ALL", "공통 규정", null));

        assertThatThrownBy(() -> documentCategoryService.createCategory(
                superAdmin(),
                new DocumentCategoryCreateRequest("ALL", "공통 규정", null)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOCUMENT_CATEGORY_NAME_DUPLICATED);
    }

    @Test
    @DisplayName("수정은 이름과 설명을 바꾼다")
    void updateCategoryChangesNameAndDescription() {
        wikiScopeRepository.save(WikiScope.all());
        DocumentCategory category = documentCategoryRepository.save(DocumentCategory.create("ALL", "기존", "기존 설명"));
        DocumentCategoryUpdateRequest request = new DocumentCategoryUpdateRequest();
        request.setName("변경");
        request.setDescription("변경 설명");

        DocumentCategoryResponse response = documentCategoryService.updateCategory(superAdmin(), category.id(), request);

        assertThat(response.name()).isEqualTo("변경");
        assertThat(response.description()).isEqualTo("변경 설명");
    }

    @Test
    @DisplayName("삭제는 문서가 사용 중인 카테고리를 409 예외로 거절한다")
    void deleteCategoryRejectsCategoryInUse() {
        wikiScopeRepository.save(WikiScope.all());
        DocumentCategory category = documentCategoryRepository.save(DocumentCategory.create("ALL", "공통", null));
        documentRepository.save(Document.uploaded(
                superAdminId, category.id(), "ALL", "rule.pdf", "original/rule.pdf", "application/pdf", 100L));

        assertThatThrownBy(() -> documentCategoryService.deleteCategory(superAdmin(), category.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOCUMENT_CATEGORY_IN_USE);
    }

    @Test
    @DisplayName("삭제는 사용하지 않는 카테고리를 제거한다")
    void deleteCategoryDeletesUnusedCategory() {
        wikiScopeRepository.save(WikiScope.all());
        DocumentCategory category = documentCategoryRepository.save(DocumentCategory.create("ALL", "공통", null));

        documentCategoryService.deleteCategory(superAdmin(), category.id());

        assertThat(documentCategoryRepository.existsById(category.id())).isFalse();
    }

    @Test
    @DisplayName("최고관리자는 전체(ALL)·타부서·복수 부서 범위 카테고리를 모두 조회할 수 있다(S15P11B106-199)")
    void superAdminReadsAnyScope() {
        wikiScopeRepository.save(WikiScope.all());
        wikiScopeRepository.save(WikiScope.department(List.of(other.getId())));
        wikiScopeRepository.save(WikiScope.department(List.of(dev.getId(), other.getId())));

        assertThat(documentCategoryService.findCategories(superAdmin(), "ALL").items()).isEmpty();
        assertThat(documentCategoryService.findCategories(superAdmin(), otherScope).items()).isEmpty();
        assertThat(documentCategoryService.findCategories(superAdmin(), multiScope).items()).isEmpty();
    }

    // ===== 부서관리자 스코프 제한(S15P11B106-199) =====

    @Test
    @DisplayName("부서관리자는 담당 부서 범위 카테고리를 생성할 수 있다")
    void deptManagerCreatesOwnScopeCategory() {
        DocumentCategoryResponse response = documentCategoryService.createCategory(
                deptManager(), new DocumentCategoryCreateRequest(devScope, "개발 규정", null));

        assertThat(response.scopeKey()).isEqualTo(devScope);
        assertThat(wikiScopeRepository.existsById(devScope)).isTrue();
    }

    @Test
    @DisplayName("부서관리자는 전체(ALL)·타부서·복수 부서 범위 카테고리를 생성할 수 없다(403)")
    void deptManagerCannotCreateOutOfScopeCategory() {
        for (String scope : List.of("ALL", otherScope, multiScope)) {
            assertThatThrownBy(() -> documentCategoryService.createCategory(
                    deptManager(), new DocumentCategoryCreateRequest(scope, "x", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("부서관리자는 담당 부서 카테고리만 조회하고 전체·타부서·복수 부서는 404로 숨겨진다")
    void deptManagerReadsOnlyOwnScope() {
        wikiScopeRepository.save(WikiScope.department(List.of(dev.getId())));
        wikiScopeRepository.save(WikiScope.all());
        wikiScopeRepository.save(WikiScope.department(List.of(other.getId())));
        wikiScopeRepository.save(WikiScope.department(List.of(dev.getId(), other.getId())));
        documentCategoryRepository.save(DocumentCategory.create(devScope, "개발 규정", null));

        assertThat(documentCategoryService.findCategories(deptManager(), devScope).items()).hasSize(1);
        for (String scope : List.of("ALL", otherScope, multiScope)) {
            assertThatThrownBy(() -> documentCategoryService.findCategories(deptManager(), scope))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.WIKI_SCOPE_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("부서관리자는 담당 부서 카테고리를 수정/삭제할 수 있다")
    void deptManagerModifiesOwnScopeCategory() {
        wikiScopeRepository.save(WikiScope.department(List.of(dev.getId())));
        DocumentCategory category = documentCategoryRepository.save(DocumentCategory.create(devScope, "개발 규정", null));
        DocumentCategoryUpdateRequest request = new DocumentCategoryUpdateRequest();
        request.setName("개발 규정 v2");

        assertThat(documentCategoryService.updateCategory(deptManager(), category.id(), request).name())
                .isEqualTo("개발 규정 v2");
        documentCategoryService.deleteCategory(deptManager(), category.id());
        assertThat(documentCategoryRepository.existsById(category.id())).isFalse();
    }

    @Test
    @DisplayName("부서관리자는 타부서 카테고리를 수정/삭제할 수 없다(404)")
    void deptManagerCannotModifyOtherScopeCategory() {
        wikiScopeRepository.save(WikiScope.department(List.of(other.getId())));
        DocumentCategory otherCat = documentCategoryRepository.save(DocumentCategory.create(otherScope, "기획 규정", null));
        DocumentCategoryUpdateRequest request = new DocumentCategoryUpdateRequest();
        request.setName("변경");

        assertThatThrownBy(() -> documentCategoryService.updateCategory(deptManager(), otherCat.id(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOCUMENT_CATEGORY_NOT_FOUND);
        assertThatThrownBy(() -> documentCategoryService.deleteCategory(deptManager(), otherCat.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOCUMENT_CATEGORY_NOT_FOUND);
    }

    private AuthenticatedMember superAdmin() {
        return new AuthenticatedMember(superAdminId, "super@ajt.com", Role.ADMIN);
    }

    private AuthenticatedMember deptManager() {
        return new AuthenticatedMember(deptManagerId, "mgr@ajt.com", Role.ADMIN);
    }

    private AuthenticatedMember employee() {
        return new AuthenticatedMember(employeeId, "employee@ajt.com", Role.EMPLOYEE);
    }
}
