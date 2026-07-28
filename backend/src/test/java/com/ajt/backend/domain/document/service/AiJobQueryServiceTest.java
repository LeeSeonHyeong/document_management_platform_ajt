package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ajt.backend.domain.document.api.AiJobResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 작업 상태 조회 서비스")
class AiJobQueryServiceTest {

    private final CurrentMemberProvider currentMemberProvider = mock(CurrentMemberProvider.class);
    private final AiJobRepository aiJobRepository = mock(AiJobRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final AiJobQueryService service = new AiJobQueryService(
            currentMemberProvider,
            aiJobRepository,
            documentRepository
    );

    @Test
    @DisplayName("관리자는 AI 작업의 전체 상태와 문서별 처리 결과를 조회할 수 있다")
    void getAiJob() throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(15L, 16L));
        assign(job, "id", 42L);
        assign(job, "createdAt", LocalDateTime.parse("2026-07-28T15:00:00"));
        job.start();
        Document completed = uploadedDocument(15L, "first.md");
        completed.startParsing();
        completed.completeParsing("wiki/ALL/sources/15/parsed.md");
        Document failed = uploadedDocument(16L, "second.md");
        failed.startParsing();
        failed.failParsing("FastAPI timeout");
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job));
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(failed, completed));

        AiJobResponse response = service.getAiJob(42L);

        assertThat(response.jobId()).isEqualTo("42");
        assertThat(response.status()).isEqualTo("processing");
        assertThat(response.createdAt()).isEqualTo(LocalDateTime.parse("2026-07-28T15:00:00"));
        assertThat(response.startedAt()).isNotNull();
        assertThat(response.finishedAt()).isNull();
        assertThat(response.failureReason()).isNull();
        assertThat(response.documentResults()).hasSize(2);
        assertThat(response.documentResults().get(0).documentId()).isEqualTo("15");
        assertThat(response.documentResults().get(0).order()).isEqualTo(1);
        assertThat(response.documentResults().get(0).status()).isEqualTo("processing");
        assertThat(response.documentResults().get(0).currentStage()).isEqualTo("wiki_pending");
        assertThat(response.documentResults().get(0).failureReason()).isNull();
        assertThat(response.documentResults().get(1).documentId()).isEqualTo("16");
        assertThat(response.documentResults().get(1).order()).isEqualTo(2);
        assertThat(response.documentResults().get(1).status()).isEqualTo("failed");
        assertThat(response.documentResults().get(1).currentStage()).isEqualTo("parsing");
        assertThat(response.documentResults().get(1).failureReason()).isEqualTo("FastAPI timeout");
    }

    @Test
    @DisplayName("관리자가 아니면 AI 작업 상태를 조회할 수 없다")
    void rejectsNonAdmin() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(20L, CurrentMemberRole.EMPLOYEE));

        assertThatThrownBy(() -> service.getAiJob(42L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("존재하지 않는 AI 작업은 404 오류로 처리한다")
    void rejectsUnknownJob() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(42L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAiJob(42L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_JOB_NOT_FOUND);
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
