package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 서버 재시작으로 중단된 작업 마감(S15P11B106-294).
 *
 * <p>PROCESSING 은 워커가 메모리에서 들고 있는 상태다. 프로세스가 죽으면 이어받을 주체가 없어
 * 영구히 PROCESSING 으로 남고, 그 문서는 수정·삭제가 막히며(S15P11B106-286) 재시도도 안 된다
 * (재시도는 FAILED·CANCELLED 만 허용).
 */
@DisplayName("중단된 AI 작업 마감")
class StuckAiJobRecoveryServiceTest {

    private final AiJobRepository aiJobRepository = mock(AiJobRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final StuckAiJobRecoveryService service =
            new StuckAiJobRecoveryService(aiJobRepository, documentRepository);

    @Test
    @DisplayName("PROCESSING 작업을 실패로 마감하고 그 문서도 함께 풀어준다")
    void failsInterruptedJobAndItsDocuments() throws Exception {
        AiJob job = processingJob(42L, List.of(15L, 16L));
        Document parsing = documentWithStatus(15L, DocumentStatus.PARSING);
        Document processing = documentWithStatus(16L, DocumentStatus.PROCESSING);
        given(aiJobRepository.findAllByStatusIn(List.of(AiJobStatus.PROCESSING))).willReturn(List.of(job));
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(parsing, processing));

        assertThat(service.failInterruptedJobs()).isEqualTo(1);

        assertThat(job.status()).isEqualTo(AiJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo(StuckAiJobRecoveryService.FAILURE_REASON);
        // 문서가 FAILED 여야 「AI 작업 요약」에서 재시도할 수 있다.
        assertThat(parsing.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(processing.status()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    @DisplayName("이미 결과가 남은 문서와 삭제 대기 문서는 건드리지 않는다")
    void leavesSettledAndDeletingDocuments() throws Exception {
        AiJob job = processingJob(42L, List.of(15L, 16L));
        Document completed = documentWithStatus(15L, DocumentStatus.COMPLETED);
        Document deleting = documentWithStatus(16L, DocumentStatus.DELETING);
        given(aiJobRepository.findAllByStatusIn(List.of(AiJobStatus.PROCESSING))).willReturn(List.of(job));
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(completed, deleting));

        service.failInterruptedJobs();

        // 작업이 중단되기 전에 끝난 문서다. 삭제 대기는 걷어내기 재시도 경로가 따로 있어,
        // 여기서 실패로 바꾸면 삭제 요청이 조용히 사라진다.
        assertThat(completed.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(deleting.status()).isEqualTo(DocumentStatus.DELETING);
    }

    @Test
    @DisplayName("중단된 작업이 없으면 아무것도 하지 않는다")
    void doesNothingWhenNoJobIsInterrupted() {
        given(aiJobRepository.findAllByStatusIn(List.of(AiJobStatus.PROCESSING))).willReturn(List.of());

        assertThat(service.failInterruptedJobs()).isZero();

        verify(aiJobRepository, never()).save(any(AiJob.class));
        verify(documentRepository, never()).findAllById(any());
    }

    private AiJob processingJob(long jobId, List<Long> documentIds) throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/abc", documentIds);
        job.start();
        assignId(job, jobId);
        return job;
    }

    private Document documentWithStatus(long documentId, DocumentStatus status) throws Exception {
        Document document = Document.uploaded(
                10L, 7L, "ALL", "rule.md", "wiki/ALL/sources/15/original.md", "text/markdown", 1024L);
        assignId(document, documentId);
        Field statusField = Document.class.getDeclaredField("status");
        statusField.setAccessible(true);
        statusField.set(document, status);
        return document;
    }

    private void assignId(Object target, long id) throws ReflectiveOperationException {
        Field idField = target.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(target, id);
    }
}
