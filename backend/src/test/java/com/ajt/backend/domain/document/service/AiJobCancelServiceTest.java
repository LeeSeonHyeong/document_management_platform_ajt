package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ajt.backend.domain.document.api.AiJobCancelResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 작업 취소 서비스")
class AiJobCancelServiceTest {

    private final CurrentMemberProvider currentMemberProvider = mock(CurrentMemberProvider.class);
    private final AiJobRepository aiJobRepository = mock(AiJobRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final AiJobCancelService service = new AiJobCancelService(
            currentMemberProvider,
            aiJobRepository,
            documentRepository
    );

    @Test
    @DisplayName("관리자는 아직 시작하지 않은 문서를 취소하고 작업 상태를 취소로 바꾼다")
    void cancelsPendingDocumentsAndJob() throws Exception {
        AiJob job = job(42L, 15L, 16L);
        job.start();
        Document uploaded = uploadedDocument(15L, "waiting.md");
        Document processing = uploadedDocument(16L, "processing.md");
        processing.startParsing();
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job));
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(uploaded, processing));

        AiJobCancelResponse response = service.cancel(42L);

        assertThat(response.jobId()).isEqualTo("42");
        assertThat(response.status()).isEqualTo("cancelled");
        assertThat(job.status()).isEqualTo(AiJobStatus.CANCELLED);
        assertThat(uploaded.status()).isEqualTo(DocumentStatus.CANCELLED);
        assertThat(processing.status()).isEqualTo(DocumentStatus.PARSING);
    }

    @Test
    @DisplayName("관리자가 아니면 AI 작업을 취소할 수 없다")
    void rejectsNonAdmin() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(20L, CurrentMemberRole.EMPLOYEE));

        assertThatThrownBy(() -> service.cancel(42L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("이미 취소된 작업은 다시 취소할 수 없다")
    void rejectsAlreadyCancelledJob() throws Exception {
        AiJob job = job(42L, 15L);
        job.cancel();
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job));

        assertThatThrownBy(() -> service.cancel(42L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    private AiJob job(long id, Long... documentIds) throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(documentIds));
        assign(job, "id", id);
        return job;
    }

    private Document uploadedDocument(long id, String fileName) throws Exception {
        Document document = Document.uploaded(
                10L,
                7L,
                "ALL",
                fileName,
                "wiki/ALL/sources/%d/original.md".formatted(id),
                "text/markdown",
                1024L
        );
        assign(document, "id", id);
        return document;
    }

    private void assign(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
