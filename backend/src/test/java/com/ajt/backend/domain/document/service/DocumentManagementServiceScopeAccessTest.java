package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.document.api.DocumentListResponse;
import com.ajt.backend.domain.document.api.DocumentSummaryResponse;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원본문서 목록 접근권한(DOC-03/FR-ACL-002)을 실제 DB 쿼리로 검증하는 통합테스트입니다.
 * 단위 테스트는 findAll을 목으로 대체해 실제 scope_key IN 필터가 실행되지 않으므로,
 * "권한 밖 문서가 정말 제외되는지"는 실 데이터로만 확인할 수 있습니다.
 * 신원(로그인 사용자)만 목으로 제어하고, 문서·공개범위 조회는 실제 리포지터리를 사용합니다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@Transactional
@DisplayName("원본문서 목록 접근권한 통합테스트")
class DocumentManagementServiceScopeAccessTest {

    @Autowired
    private DocumentManagementService documentManagementService;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private WikiScopeRepository wikiScopeRepository;
    @Autowired
    private DocumentCategoryRepository documentCategoryRepository;
    @Autowired
    private DocumentRepository documentRepository;

    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;

    private long employeeId;
    private long department2Id;
    private String scopeD2;
    private String scopeD3;

    @BeforeEach
    void seed() {
        Department department2 = departmentRepository.save(new Department("영업부"));
        Department department3 = departmentRepository.save(new Department("기획부"));
        department2Id = department2.getId();
        employeeId = memberRepository.save(Member.approvedEmployee(
                department2, "emp@ajt.com", "사원", "hash", "AJT-2026-0001")).getId();

        // 공개범위: 전체(ALL), 2번 부서(D2), 3번 부서(D3)
        wikiScopeRepository.save(WikiScope.all());
        scopeD2 = wikiScopeRepository.save(WikiScope.department(List.of(department2.getId()))).scopeKey();
        scopeD3 = wikiScopeRepository.save(WikiScope.department(List.of(department3.getId()))).scopeKey();

        long categoryAll = documentCategoryRepository.save(DocumentCategory.create("ALL", "공통", null)).id();
        long categoryD2 = documentCategoryRepository.save(DocumentCategory.create(scopeD2, "영업", null)).id();
        long categoryD3 = documentCategoryRepository.save(DocumentCategory.create(scopeD3, "기획", null)).id();

        // 각 공개범위마다 문서 1건
        documentRepository.save(Document.uploaded(
                employeeId, categoryAll, "ALL", "all.md", "wiki/ALL/sources/all.md", "text/markdown", 100L));
        documentRepository.save(Document.uploaded(
                employeeId, categoryD2, scopeD2, "d2.md", "wiki/" + scopeD2 + "/sources/d2.md", "text/markdown", 100L));
        documentRepository.save(Document.uploaded(
                employeeId, categoryD3, scopeD3, "d3.md", "wiki/" + scopeD3 + "/sources/d3.md", "text/markdown", 100L));
    }

    @Test
    @DisplayName("사원은 전체 공개(ALL)와 본인 소속 부서(D2) 문서만 보고, 다른 부서(D3) 문서는 제외된다")
    void employeeSeesOnlyAccessibleScopes() {
        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(employeeId, CurrentMemberRole.EMPLOYEE));

        DocumentListResponse response = documentManagementService.findDocuments(
                1, 100, null, null, null, null, null, null, null, null, null);

        List<String> scopeKeys = response.items().stream()
                .map(DocumentSummaryResponse::scopeKey)
                .toList();
        assertThat(scopeKeys).containsExactlyInAnyOrder("ALL", scopeD2);
        assertThat(scopeKeys).doesNotContain(scopeD3);
    }

    @Test
    @DisplayName("관리자는 접근 제한 없이 모든 공개범위의 문서를 본다")
    void adminSeesAllScopes() {
        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(employeeId, CurrentMemberRole.ADMIN));

        DocumentListResponse response = documentManagementService.findDocuments(
                1, 100, null, null, null, null, null, null, null, null, null);

        List<String> scopeKeys = response.items().stream()
                .map(DocumentSummaryResponse::scopeKey)
                .toList();
        assertThat(scopeKeys).containsExactlyInAnyOrder("ALL", scopeD2, scopeD3);
    }

    @Test
    @DisplayName("departmentId로 필터하면 해당 부서 전용 문서만 나오고 전체 공개(ALL)는 제외된다(의도된 동작)")
    void departmentFilterExcludesAllScope() {
        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(employeeId, CurrentMemberRole.ADMIN));

        DocumentListResponse response = documentManagementService.findDocuments(
                1, 100, null, null, null, null, null, department2Id, null, null, null);

        List<String> scopeKeys = response.items().stream()
                .map(DocumentSummaryResponse::scopeKey)
                .toList();
        assertThat(scopeKeys).containsExactly(scopeD2);
        assertThat(scopeKeys).doesNotContain("ALL", scopeD3);
    }
}
