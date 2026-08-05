package com.ajt.backend.domain.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false",
        // ensureForDepartment를 직접 호출해 검증하므로 시작 시 자동 생성 러너는 끄고 격리한다.
        "ajt.default-department.enabled=false"
})
@Transactional
@DisplayName("부서 기본 문서 카테고리 보장(S15P11B106-257)")
class DefaultDocumentCategoryEnsurerTest {

    private final DefaultDocumentCategoryEnsurer ensurer;
    private final DocumentCategoryRepository documentCategoryRepository;
    private final WikiScopeRepository wikiScopeRepository;

    @Autowired
    DefaultDocumentCategoryEnsurerTest(
            DefaultDocumentCategoryEnsurer ensurer,
            DocumentCategoryRepository documentCategoryRepository,
            WikiScopeRepository wikiScopeRepository
    ) {
        this.ensurer = ensurer;
        this.documentCategoryRepository = documentCategoryRepository;
        this.wikiScopeRepository = wikiScopeRepository;
    }

    @Test
    @DisplayName("부서 문서 공간(wiki_scope)과 기본 카테고리 4개를 생성한다")
    void createsDepartmentScopeAndDefaultCategories() {
        long departmentId = 7L;
        String scopeKey = "D" + departmentId;

        ensurer.ensureForDepartment(departmentId);

        // 카테고리 목록 조회(CAT-01)에 필요한 부서 문서 공간이 함께 생긴다.
        assertThat(wikiScopeRepository.existsById(scopeKey)).isTrue();
        assertThat(documentCategoryRepository.findAllByScopeKeyOrderByNameAsc(scopeKey))
                .extracting(DocumentCategory::name)
                .containsExactlyInAnyOrderElementsOf(DefaultDocumentCategoryEnsurer.DEFAULT_CATEGORY_NAMES);
    }

    @Test
    @DisplayName("이미 있으면 중복 생성하지 않는다(idempotent)")
    void doesNotDuplicateWhenAlreadyPresent() {
        long departmentId = 9L;
        String scopeKey = "D" + departmentId;

        ensurer.ensureForDepartment(departmentId);
        ensurer.ensureForDepartment(departmentId);

        assertThat(documentCategoryRepository.findAllByScopeKeyOrderByNameAsc(scopeKey))
                .hasSize(DefaultDocumentCategoryEnsurer.DEFAULT_CATEGORY_NAMES.size());
    }

    @Test
    @DisplayName("관리자가 지운 기본 카테고리는 되살리지 않는다 — 없는 이름만 채운다")
    void onlyFillsMissingNames() {
        long departmentId = 11L;
        String scopeKey = "D" + departmentId;
        ensurer.ensureForDepartment(departmentId);

        // 관리자가 카테고리 하나를 삭제한 상황을 재현한다.
        DocumentCategory toDelete = documentCategoryRepository.findAllByScopeKeyOrderByNameAsc(scopeKey).stream()
                .filter(category -> "기타 자료".equals(category.name()))
                .findFirst()
                .orElseThrow();
        documentCategoryRepository.delete(toDelete);

        // 같은 부서에 다시 호출해도(비정상 경로) 지운 것만 다시 채우고 나머지는 그대로 둔다.
        ensurer.ensureForDepartment(departmentId);

        assertThat(documentCategoryRepository.findAllByScopeKeyOrderByNameAsc(scopeKey))
                .extracting(DocumentCategory::name)
                .containsExactlyInAnyOrderElementsOf(DefaultDocumentCategoryEnsurer.DEFAULT_CATEGORY_NAMES);
    }
}
