package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@Transactional
@DisplayName("문서 카테고리 서비스")
class DocumentCategoryServiceTest {

    private final DocumentCategoryService documentCategoryService;
    private final DocumentCategoryRepository documentCategoryRepository;
    private final DocumentRepository documentRepository;
    private final WikiScopeRepository wikiScopeRepository;

    @Autowired
    DocumentCategoryServiceTest(
            DocumentCategoryService documentCategoryService,
            DocumentCategoryRepository documentCategoryRepository,
            DocumentRepository documentRepository,
            WikiScopeRepository wikiScopeRepository
    ) {
        this.documentCategoryService = documentCategoryService;
        this.documentCategoryRepository = documentCategoryRepository;
        this.documentRepository = documentRepository;
        this.wikiScopeRepository = wikiScopeRepository;
    }

    @Test
    @DisplayName("목록 조회는 같은 Wiki 공간의 카테고리만 이름순으로 반환한다")
    void findCategoriesReturnsScopeCategoriesByName() {
        wikiScopeRepository.save(WikiScope.department(java.util.List.of(1L)));
        documentCategoryRepository.save(DocumentCategory.create("D1", "휴가", null));
        documentCategoryRepository.save(DocumentCategory.create("D1", "근태", null));
        documentCategoryRepository.save(DocumentCategory.create("ALL", "공통", null));

        DocumentCategoryListResponse response = documentCategoryService.findCategories("D1");

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).name()).isEqualTo("근태");
        assertThat(response.items().get(1).name()).isEqualTo("휴가");
    }

    @Test
    @DisplayName("목록 조회는 존재하지 않는 Wiki 공간이면 404 예외를 발생시킨다")
    void findCategoriesRejectsMissingScope() {
        assertThatThrownBy(() -> documentCategoryService.findCategories("D999"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_SCOPE_NOT_FOUND);
    }

    @Test
    @DisplayName("생성은 관리자가 카테고리를 저장하고 없는 Wiki 공간은 함께 준비한다")
    void createCategoryCreatesCategoryAndScope() {
        DocumentCategoryResponse response = documentCategoryService.createCategory(
                admin(),
                new DocumentCategoryCreateRequest("D1-D2", " 사내 규정 ", "공통 규정")
        );

        assertThat(response.scopeKey()).isEqualTo("D1-D2");
        assertThat(response.name()).isEqualTo("사내 규정");
        assertThat(wikiScopeRepository.existsById("D1-D2")).isTrue();
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
                admin(),
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

        DocumentCategoryResponse response = documentCategoryService.updateCategory(admin(), category.id(), request);

        assertThat(response.name()).isEqualTo("변경");
        assertThat(response.description()).isEqualTo("변경 설명");
    }

    @Test
    @DisplayName("삭제는 문서가 사용 중인 카테고리를 409 예외로 거절한다")
    void deleteCategoryRejectsCategoryInUse() {
        wikiScopeRepository.save(WikiScope.all());
        DocumentCategory category = documentCategoryRepository.save(DocumentCategory.create("ALL", "공통", null));
        documentRepository.save(Document.uploaded(
                1L,
                category.id(),
                "ALL",
                "rule.pdf",
                "original/rule.pdf",
                "application/pdf",
                100L
        ));

        assertThatThrownBy(() -> documentCategoryService.deleteCategory(admin(), category.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOCUMENT_CATEGORY_IN_USE);
    }

    @Test
    @DisplayName("삭제는 사용하지 않는 카테고리를 제거한다")
    void deleteCategoryDeletesUnusedCategory() {
        wikiScopeRepository.save(WikiScope.all());
        DocumentCategory category = documentCategoryRepository.save(DocumentCategory.create("ALL", "공통", null));

        documentCategoryService.deleteCategory(admin(), category.id());

        assertThat(documentCategoryRepository.existsById(category.id())).isFalse();
    }

    private AuthenticatedMember admin() {
        return new AuthenticatedMember(1L, "admin@ajt.com", Role.ADMIN);
    }

    private AuthenticatedMember employee() {
        return new AuthenticatedMember(2L, "employee@ajt.com", Role.EMPLOYEE);
    }
}
