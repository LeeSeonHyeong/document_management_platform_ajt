package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.document.api.DocumentDeleteResponse;
import com.ajt.backend.domain.document.api.DocumentDetailResponse;
import com.ajt.backend.domain.document.api.DocumentListResponse;
import com.ajt.backend.domain.document.api.DocumentMetadataUpdateRequest;
import com.ajt.backend.domain.document.api.DocumentRetryResponse;
import com.ajt.backend.domain.document.api.DocumentSummaryResponse;
import com.ajt.backend.domain.document.api.DocumentUpdateResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.model.WikiScopeVisibilityType;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.lang.reflect.Field;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@DisplayName("원본문서 관리 서비스")
class DocumentManagementServiceTest {

    private final CurrentMemberProvider currentMemberProvider = mock(CurrentMemberProvider.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentCategoryRepository documentCategoryRepository = mock(DocumentCategoryRepository.class);
    private final AiJobRepository aiJobRepository = mock(AiJobRepository.class);
    private final DocumentParseJobLauncher parseJobLauncher = mock(DocumentParseJobLauncher.class);
    private final DocumentFileStorage documentFileStorage = mock(DocumentFileStorage.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final WikiScopeRepository wikiScopeRepository = mock(WikiScopeRepository.class);
    private final DocumentManagementService service = new DocumentManagementService(
            currentMemberProvider,
            documentRepository,
            documentCategoryRepository,
            aiJobRepository,
            parseJobLauncher,
            documentFileStorage,
            memberRepository,
            wikiScopeRepository
    );

    @Test
    @DisplayName("관리자는 문서 상세와 처리 상태를 조회할 수 있다")
    void getDocumentDetail() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        assignTime(document, "createdAt", Instant.parse("2026-07-28T05:00:00Z"));
        assignTime(document, "updatedAt", Instant.parse("2026-07-28T05:01:00Z"));
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentCategoryRepository.findById(7L))
                .willReturn(Optional.of(category(7L, "취업규칙")));

        DocumentDetailResponse response = service.getDocument(15L);

        assertThat(response.documentId()).isEqualTo("15");
        assertThat(response.originalFileName()).isEqualTo("rule.md");
        assertThat(response.scopeKey()).isEqualTo("ALL");
        assertThat(response.status()).isEqualTo("uploaded");
        assertThat(response.category().documentCategoryId()).isEqualTo("7");
        assertThat(response.category().name()).isEqualTo("취업규칙");
        assertThat(response.downloadUrl()).isEqualTo("/api/v1/documents/15/file");
        assertThat(response.relatedWikis()).isEmpty();
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-07-28T05:00:00Z"));
        assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-07-28T05:01:00Z"));
    }

    @Test
    @DisplayName("관리자가 아니면 문서 관리 기능을 사용할 수 없다")
    void rejectsNonAdmin() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(20L, CurrentMemberRole.EMPLOYEE));

        assertThatThrownBy(() -> service.getDocument(15L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("관리자는 원본문서 파일을 원래 파일명·타입으로 다운로드할 수 있다")
    void downloadFileForAdmin() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        Resource resource = new ByteArrayResource("hello".getBytes());
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentFileStorage.load("wiki/ALL/sources/15/original.md")).willReturn(resource);

        DocumentFileDownload download = service.downloadFile(15L);

        assertThat(download.fileName()).isEqualTo("rule.md");
        assertThat(download.contentType()).isEqualTo("text/markdown");
        assertThat(download.resource()).isSameAs(resource);
    }

    @Test
    @DisplayName("관리자가 아니면 파일을 다운로드할 수 없다")
    void rejectsNonAdminDownload() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(20L, CurrentMemberRole.EMPLOYEE));

        assertThatThrownBy(() -> service.downloadFile(15L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("실제 파일이 없으면(유실) 다운로드는 404를 반환한다")
    void downloadFileReturnsNotFoundWhenFileMissing() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        Resource missing = mock(Resource.class);
        given(missing.isReadable()).willReturn(false);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentFileStorage.load("wiki/ALL/sources/15/original.md")).willReturn(missing);

        assertThatThrownBy(() -> service.downloadFile(15L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("실패 문서는 재시도 시 대기 상태로 되돌리고 새 AI 작업을 생성한다")
    void retryFailedDocument() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        document.startParsing();
        document.failParsing("파싱 실패");
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        DocumentRetryResponse response = service.retry(15L);

        assertThat(response.jobId()).isEqualTo("42");
        assertThat(response.documentId()).isEqualTo("15");
        assertThat(response.status()).isEqualTo("waiting");
        assertThat(document.status().name()).isEqualTo("UPLOADED");
        assertThat(document.failureReason()).isNull();
        verify(parseJobLauncher).launch(any(AiJob.class));
    }

    @Test
    @DisplayName("실패 또는 취소 상태가 아닌 문서는 재시도할 수 없다")
    void rejectsRetryForNonFailedDocument() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> service.retry(15L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_DOCUMENT_STATUS);
    }

    @Test
    @DisplayName("관리자는 접근범위 제한 없이 필터·페이지 정보로 전체 목록을 조회한다")
    void findDocumentsForAdmin() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        assignTime(document, "createdAt", Instant.parse("2026-07-28T05:00:00Z"));
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(document), PageRequest.of(0, 20), 1));
        given(documentCategoryRepository.findAllById(any())).willReturn(List.of(category(7L, "취업규칙")));

        DocumentListResponse response =
                service.findDocuments(1, 20, "ALL", null, "uploaded", null, null, null, null, null, null);

        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.items()).hasSize(1);
        DocumentSummaryResponse item = response.items().get(0);
        assertThat(item.documentId()).isEqualTo("15");
        assertThat(item.originalFileName()).isEqualTo("rule.md");
        assertThat(item.scopeKey()).isEqualTo("ALL");
        assertThat(item.status()).isEqualTo("uploaded");
        assertThat(item.category().name()).isEqualTo("취업규칙");
        // 관리자는 접근범위 계산이 필요 없으므로 부서/공개범위 조회를 하지 않는다.
        verify(memberRepository, never()).findById(anyLong());
        verify(wikiScopeRepository, never()).findByVisibilityType(any());
    }

    @Test
    @DisplayName("사원은 접근 가능한 문서만 조회하며, 소속 부서·공개범위를 조회해 필터를 만든다")
    void findDocumentsForEmployee() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(20L, CurrentMemberRole.EMPLOYEE));
        // 사원(20)의 소속 부서 = 2
        Department department = mock(Department.class);
        given(department.getId()).willReturn(2L);
        Member member = mock(Member.class);
        given(member.getDepartment()).willReturn(department);
        given(memberRepository.findById(20L)).willReturn(Optional.of(member));
        // 부서 2를 포함하는 공개범위(D2)가 존재
        WikiScope departmentScope = mock(WikiScope.class);
        given(departmentScope.departmentRefs()).willReturn(List.of(2L));
        given(departmentScope.scopeKey()).willReturn("D2");
        given(wikiScopeRepository.findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT))
                .willReturn(List.of(departmentScope));
        given(documentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(document), PageRequest.of(0, 20), 1));
        given(documentCategoryRepository.findAllById(any())).willReturn(List.of(category(7L, "취업규칙")));

        DocumentListResponse response =
                service.findDocuments(1, 20, null, null, null, null, null, null, null, null, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).documentId()).isEqualTo("15");
        verify(memberRepository).findById(20L);
        verify(wikiScopeRepository).findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT);
    }

    @Test
    @DisplayName("departmentId 필터를 주면 해당 부서를 포함하는 공개범위를 조회해 필터로 사용한다")
    void findDocumentsWithDepartmentFilter() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        WikiScope departmentScope = mock(WikiScope.class);
        given(departmentScope.departmentRefs()).willReturn(List.of(2L));
        given(departmentScope.scopeKey()).willReturn("D2");
        given(wikiScopeRepository.findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT))
                .willReturn(List.of(departmentScope));
        given(documentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(document), PageRequest.of(0, 20), 1));
        given(documentCategoryRepository.findAllById(any())).willReturn(List.of(category(7L, "취업규칙")));

        DocumentListResponse response =
                service.findDocuments(1, 20, null, null, null, null, null, 2L, null, null, null);

        assertThat(response.items()).hasSize(1);
        // 관리자여도 departmentId 필터가 있으면 공개범위 조회가 일어난다.
        verify(wikiScopeRepository).findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT);
        verify(memberRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("관리자는 문서 메타데이터를 수정하고 재처리 작업을 생성한다")
    void updatesDocumentMetadata() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentCategoryRepository.findById(7L)).willReturn(Optional.of(category(7L, "취업규칙")));
        given(wikiScopeRepository.findById("ALL")).willReturn(Optional.of(mock(WikiScope.class)));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        DocumentUpdateResponse response = service.update(
                15L, new DocumentMetadataUpdateRequest(7L, "all", List.of()));

        assertThat(response.jobId()).isEqualTo("42");
        assertThat(response.status()).isEqualTo("waiting");
        assertThat(response.document().documentId()).isEqualTo("15");
        assertThat(response.document().category().name()).isEqualTo("취업규칙");
        assertThat(document.status().name()).isEqualTo("UPLOADED");
        verify(parseJobLauncher).launch(any(AiJob.class));
        // 같은 범위 수정은 이 문서만 증분 재처리한다(범위 전체를 훑지 않는다).
        verify(documentRepository, never()).findByScopeKey(any());
    }

    @Test
    @DisplayName("공개 범위가 바뀌면 새 범위엔 이 문서만, 기존 범위엔 남은 문서로 재처리한다")
    void updatesDocumentMetadataWithScopeChange() throws Exception {
        Document document = uploadedDocument(); // scope "ALL"
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentCategoryRepository.findById(4L)).willReturn(Optional.of(category(4L, "D1-D3", "사규")));
        given(wikiScopeRepository.findById("D1-D3")).willReturn(Optional.of(mock(WikiScope.class)));
        // 기존 범위 검사에서는 [문서], 문서 이동 후 재처리에서는 [] (남은 문서 없음)
        given(documentRepository.findByScopeKey("ALL")).willReturn(List.of(document), List.of());
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        DocumentUpdateResponse response = service.update(
                15L, new DocumentMetadataUpdateRequest(4L, "department", List.of(1L, 3L)));

        assertThat(response.jobId()).isEqualTo("42");
        assertThat(response.document().scopeKey()).isEqualTo("D1-D3");
        assertThat(response.document().category().name()).isEqualTo("사규");
        assertThat(document.scopeKey()).isEqualTo("D1-D3");
        assertThat(document.status().name()).isEqualTo("UPLOADED");
        // 새 범위(문서 단위) + 기존 범위(범위 단위) → 재처리 작업 2건
        verify(parseJobLauncher, times(2)).launch(any(AiJob.class));
    }

    @Test
    @DisplayName("관리자가 아니면 문서를 수정할 수 없다")
    void rejectsNonAdminUpdate() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(20L, CurrentMemberRole.EMPLOYEE));

        assertThatThrownBy(() -> service.update(15L, new DocumentMetadataUpdateRequest(7L, "all", List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("처리 중인 문서는 수정할 수 없다(409)")
    void rejectsUpdateWhenProcessing() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        document.startParsing(); // UPLOADED -> PARSING (처리 중)
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> service.update(15L, new DocumentMetadataUpdateRequest(7L, "all", List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_DOCUMENT_STATUS);
    }

    @Test
    @DisplayName("관리자는 문서를 삭제하고 남은 문서 기준 재처리 작업을 생성한다")
    void deletesDocument() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        // 삭제 전 검사에서는 [문서], 삭제 후 재처리에서는 [] (남은 문서 없음)
        given(documentRepository.findByScopeKey("ALL")).willReturn(List.of(document), List.of());
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        DocumentDeleteResponse response = service.delete(15L);

        assertThat(response.jobId()).isEqualTo("42");
        assertThat(response.scopeKey()).isEqualTo("ALL");
        assertThat(response.status()).isEqualTo("waiting");
        verify(documentRepository).delete(document);
        verify(parseJobLauncher).launch(any(AiJob.class));
    }

    @Test
    @DisplayName("관리자가 아니면 문서를 삭제할 수 없다")
    void rejectsNonAdminDelete() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(20L, CurrentMemberRole.EMPLOYEE));

        assertThatThrownBy(() -> service.delete(15L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private Document uploadedDocument() {
        return Document.uploaded(
                10L,
                7L,
                "ALL",
                "rule.md",
                "wiki/ALL/sources/15/original.md",
                "text/markdown",
                1024L
        );
    }

    private DocumentCategory category(long id, String name) throws Exception {
        return category(id, "ALL", name);
    }

    private DocumentCategory category(long id, String scopeKey, String name) throws Exception {
        DocumentCategory category = DocumentCategory.create(scopeKey, name, null);
        assignId(category, id);
        return category;
    }

    private void assignId(Object target, long id) throws ReflectiveOperationException {
        Field idField = target.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(target, id);
    }

    private void assignTime(Document document, String fieldName, Instant value) throws ReflectiveOperationException {
        Field field = Document.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(document, value);
    }
}
