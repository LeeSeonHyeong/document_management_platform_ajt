package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ajt.backend.domain.document.api.DocumentUploadRequest;
import com.ajt.backend.domain.document.api.DocumentUploadValidationException;
import com.ajt.backend.domain.document.api.DocumentUploadResponse;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.member.DepartmentScopePolicy;
import com.ajt.backend.domain.member.ScopeAccess;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

@DisplayName("원본문서 업로드 서비스")
class DocumentUploadServiceTest {

    private final CurrentMemberProvider currentMemberProvider = mock(CurrentMemberProvider.class);
    private final WikiScopeRepository wikiScopeRepository = mock(WikiScopeRepository.class);
    private final DocumentCategoryRepository documentCategoryRepository = mock(DocumentCategoryRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentFileStorage fileStorage = mock(DocumentFileStorage.class);
    private final DepartmentScopePolicy departmentScopePolicy = superAdminScopePolicy();
    private final DocumentUploadService service = new DocumentUploadService(
            currentMemberProvider,
            wikiScopeRepository,
            documentCategoryRepository,
            documentRepository,
            fileStorage,
            departmentScopePolicy
    );

    // 기존 테스트의 관리자는 전체 접근(최고관리자)으로 취급해 기존 동작을 유지한다(S15P11B106-199).
    private static DepartmentScopePolicy superAdminScopePolicy() {
        DepartmentScopePolicy policy = mock(DepartmentScopePolicy.class);
        given(policy.resolve(anyLong())).willReturn(ScopeAccess.superAdmin());
        return policy;
    }

    @Test
    @DisplayName("부서관리자는 타부서(D3) 범위로 업로드할 수 없다(S15P11B106-199)")
    void departmentManagerCannotUploadToAnotherDepartment() {
        DocumentUploadRequest request = DocumentUploadRequest.of(
                List.of(markdownFile("a.md")), 7L, "department", List.of(3L));
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(departmentScopePolicy.resolve(10L)).willReturn(ScopeAccess.departmentManager(2L));

        assertThatThrownBy(() -> service.upload(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("부서관리자는 전체 공개(ALL) 범위로 업로드할 수 있다(S15P11B106-289)")
    void departmentManagerUploadsToAllScope() throws Exception {
        MockMultipartFile file = markdownFile("a.md");
        DocumentUploadRequest request = DocumentUploadRequest.of(List.of(file), 7L, "all", List.of());
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(departmentScopePolicy.resolve(10L)).willReturn(ScopeAccess.departmentManager(2L));
        given(documentCategoryRepository.findById(7L))
                .willReturn(Optional.of(DocumentCategory.create("ALL", "공지사항", null)));
        given(wikiScopeRepository.findById("ALL")).willReturn(Optional.of(WikiScope.all()));
        given(documentRepository.save(any(Document.class))).willAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            assignId(document, 15L);
            return document;
        });
        given(fileStorage.storeOriginal("ALL", 15L, file)).willReturn("ALL/15/original.md");

        assertThat(service.upload(request).scopeKey()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("관리자 업로드는 scope와 문서들을 생성하고 202 응답 데이터를 반환한다")
    void createsDocuments() throws Exception {
        MockMultipartFile first = markdownFile("one.md");
        MockMultipartFile second = markdownFile("two.md");
        DocumentUploadRequest request = DocumentUploadRequest.of(
                List.of(first, second), 7L, "department", List.of(2L, 1L)
        );
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentCategoryRepository.findById(7L))
                .willReturn(Optional.of(DocumentCategory.create("D1-D2", "인사규정", null)));
        given(wikiScopeRepository.findById("D1-D2")).willReturn(Optional.empty());
        given(wikiScopeRepository.save(any(WikiScope.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(documentRepository.save(any(Document.class))).willAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            assignId(document, nextDocumentId++);
            return document;
        });
        given(fileStorage.storeOriginal("D1-D2", 15L, first)).willReturn("D1-D2/15/original.md");
        given(fileStorage.storeOriginal("D1-D2", 16L, second)).willReturn("D1-D2/16/original.md");

        DocumentUploadResponse response = service.upload(request);

        assertThat(response.documentIds()).containsExactly("15", "16");
        assertThat(response.scopeKey()).isEqualTo("D1-D2");
        assertThat(response.status()).isEqualTo("uploaded");
        assertThat(response.createdAt()).isNotNull();
        verify(wikiScopeRepository).save(any(WikiScope.class));
    }

    @Test
    @DisplayName("카테고리·공개 범위 없이 올리면 카테고리 null로 임시 scope(최고관리자는 ALL)에 저장한다")
    void uploadsWithoutClassification() throws Exception {
        MockMultipartFile file = markdownFile("one.md");
        DocumentUploadRequest request = DocumentUploadRequest.of(List.of(file), null, null, List.of());
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(wikiScopeRepository.findById("ALL")).willReturn(Optional.of(WikiScope.all()));
        given(documentRepository.save(any(Document.class))).willAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            assignId(document, 15L);
            return document;
        });
        given(fileStorage.storeOriginal("ALL", 15L, file)).willReturn("ALL/15/original.md");

        DocumentUploadResponse response = service.upload(request);

        assertThat(response.scopeKey()).isEqualTo("ALL");
        assertThat(response.status()).isEqualTo("uploaded");
        ArgumentCaptor<Document> savedDocuments = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(savedDocuments.capture());
        assertThat(savedDocuments.getValue().documentCategoryId()).isNull();
        assertThat(savedDocuments.getValue().isClassified()).isFalse();
        // 카테고리를 지정하지 않았으므로 카테고리 조회 자체가 없어야 한다.
        verify(documentCategoryRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("부서관리자가 분류 없이 올리면 담당 부서 scope에 저장한다")
    void uploadsWithoutClassificationToManagedScope() throws Exception {
        MockMultipartFile file = markdownFile("one.md");
        DocumentUploadRequest request = DocumentUploadRequest.of(List.of(file), null, null, List.of());
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(departmentScopePolicy.resolve(10L)).willReturn(ScopeAccess.departmentManager(2L));
        given(wikiScopeRepository.findById("D2")).willReturn(Optional.of(WikiScope.department(List.of(2L))));
        given(documentRepository.save(any(Document.class))).willAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            assignId(document, 15L);
            return document;
        });
        given(fileStorage.storeOriginal("D2", 15L, file)).willReturn("D2/15/original.md");

        assertThat(service.upload(request).scopeKey()).isEqualTo("D2");
    }

    @Test
    @DisplayName("카테고리만 오고 공개 범위가 없으면 요청을 거부한다")
    void rejectsCategoryWithoutVisibility() {
        assertThatThrownBy(() -> DocumentUploadRequest.of(
                List.of(markdownFile("one.md")), 7L, null, List.of()))
                .isInstanceOf(DocumentUploadValidationException.class);
    }

    @Test
    @DisplayName("카테고리 공개 범위가 요청 공개 범위와 다르면 업로드를 거부한다")
    void rejectsCategoryFromAnotherScope() {
        DocumentUploadRequest request = DocumentUploadRequest.of(
                List.of(markdownFile("one.md")), 7L, "all", List.of()
        );
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentCategoryRepository.findById(7L))
                .willReturn(Optional.of(DocumentCategory.create("D1", "부서규정", null)));

        assertThatThrownBy(() -> service.upload(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_DOCUMENT_UPLOAD);
    }

    @Test
    @DisplayName("문서 저장이 중간에 실패하면 앞서 저장된 원본 파일을 삭제한다")
    void deletesStoredOriginalFilesWhenSaveFails() throws Exception {
        MockMultipartFile file = markdownFile("one.md");
        MockMultipartFile second = markdownFile("two.md");
        DocumentUploadRequest request = DocumentUploadRequest.of(List.of(file, second), 7L, "all", List.of());
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentCategoryRepository.findById(7L))
                .willReturn(Optional.of(DocumentCategory.create("ALL", "공통규정", null)));
        given(wikiScopeRepository.findById("ALL")).willReturn(Optional.of(WikiScope.all()));
        given(documentRepository.save(any(Document.class)))
                .willAnswer(invocation -> {
                    Document document = invocation.getArgument(0);
                    assignId(document, 15L);
                    return document;
                })
                .willThrow(new RuntimeException("DB failure"));
        given(fileStorage.storeOriginal("ALL", 15L, file)).willReturn("ALL/15/original.md");

        assertThatThrownBy(() -> service.upload(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB failure");
        verify(fileStorage).delete("ALL/15/original.md");
    }

    private long nextDocumentId = 15L;

    private MockMultipartFile markdownFile(String name) {
        return new MockMultipartFile("files", name, "text/markdown", "# 문서".getBytes());
    }

    private void assignId(Object target, long id) throws ReflectiveOperationException {
        Field idField = target.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(target, id);
    }
}
