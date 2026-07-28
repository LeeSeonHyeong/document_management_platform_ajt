package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ajt.backend.domain.document.api.DocumentDetailResponse;
import com.ajt.backend.domain.document.api.DocumentRetryResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("원본문서 관리 서비스")
class DocumentManagementServiceTest {

    private final CurrentMemberProvider currentMemberProvider = mock(CurrentMemberProvider.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentCategoryRepository documentCategoryRepository = mock(DocumentCategoryRepository.class);
    private final AiJobRepository aiJobRepository = mock(AiJobRepository.class);
    private final DocumentParseJobLauncher parseJobLauncher = mock(DocumentParseJobLauncher.class);
    private final DocumentManagementService service = new DocumentManagementService(
            currentMemberProvider,
            documentRepository,
            documentCategoryRepository,
            aiJobRepository,
            parseJobLauncher
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
        DocumentCategory category = DocumentCategory.create("ALL", name, null);
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
