package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ajt.backend.domain.document.api.DocumentUploadRequest;
import com.ajt.backend.domain.document.api.DocumentUploadResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

@DisplayName("원본문서 업로드 서비스")
class DocumentUploadServiceTest {

    private final CurrentMemberProvider currentMemberProvider = mock(CurrentMemberProvider.class);
    private final WikiScopeRepository wikiScopeRepository = mock(WikiScopeRepository.class);
    private final DocumentCategoryRepository documentCategoryRepository = mock(DocumentCategoryRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final AiJobRepository aiJobRepository = mock(AiJobRepository.class);
    private final DocumentFileStorage fileStorage = mock(DocumentFileStorage.class);
    private final DocumentParseJobLauncher parseJobLauncher = mock(DocumentParseJobLauncher.class);
    private final DocumentUploadService service = new DocumentUploadService(
            currentMemberProvider,
            wikiScopeRepository,
            documentCategoryRepository,
            documentRepository,
            aiJobRepository,
            fileStorage,
            parseJobLauncher
    );

    @Test
    @DisplayName("관리자 업로드는 scope, 문서들, 대기 AI 작업을 생성하고 202 응답 데이터를 반환한다")
    void createsDocumentsAndWaitingJob() throws Exception {
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
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        DocumentUploadResponse response = service.upload(request);

        assertThat(response.jobId()).isEqualTo("42");
        assertThat(response.documentIds()).containsExactly("15", "16");
        assertThat(response.scopeKey()).isEqualTo("D1-D2");
        assertThat(response.status()).isEqualTo("waiting");
        assertThat(response.createdAt()).isNotNull();
        verify(wikiScopeRepository).save(any(WikiScope.class));
        verify(parseJobLauncher).launch(any(AiJob.class), any(DocumentReprocessPlan.class));
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
    @DisplayName("AI 작업 생성이 실패하면 저장된 원본 파일을 삭제한다")
    void deletesStoredOriginalFilesWhenJobCreationFails() throws Exception {
        MockMultipartFile file = markdownFile("one.md");
        DocumentUploadRequest request = DocumentUploadRequest.of(List.of(file), 7L, "all", List.of());
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentCategoryRepository.findById(7L))
                .willReturn(Optional.of(DocumentCategory.create("ALL", "공통규정", null)));
        given(wikiScopeRepository.findById("ALL")).willReturn(Optional.of(WikiScope.all()));
        given(documentRepository.save(any(Document.class))).willAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            assignId(document, 15L);
            return document;
        });
        given(fileStorage.storeOriginal("ALL", 15L, file)).willReturn("ALL/15/original.md");
        doThrow(new RuntimeException("DB failure")).when(aiJobRepository).save(any(AiJob.class));

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
