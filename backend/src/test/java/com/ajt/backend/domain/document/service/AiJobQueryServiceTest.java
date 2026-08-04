package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ajt.backend.domain.document.api.AiJobListResponse;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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
    @DisplayName("종료된 작업은 기록된 문서별 요약과 실패 단계를 함께 돌려준다")
    void getFinishedAiJob() throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(15L, 16L));
        assign(job, "id", 42L);
        assign(job, "createdAt", LocalDateTime.parse("2026-07-28T15:00:00"));
        job.start();
        Document completed = uploadedDocument(15L, "first.md");
        completed.startParsing();
        completed.completeParsing("wiki/ALL/sources/15/parsed.md");
        completed.completeProcessing(List.of(101L));
        Document failed = uploadedDocument(16L, "second.md");
        failed.startParsing();
        failed.completeParsing("wiki/ALL/sources/16/parsed.md");
        failed.failProcessing("Wiki 변환에 실패했습니다.");
        job.finish(List.of(
                AiJob.DocumentParseResult.succeeded(15L, "문서-15.pdf", "휴가 규정을 Wiki에 반영했습니다."),
                AiJob.DocumentParseResult.failed(16L, "문서-16.pdf", "Wiki 변환에 실패했습니다.", "agent_timeout")
        ));
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job));
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(completed, failed));

        AiJobResponse response = service.getAiJob(42L);

        assertThat(response.status()).isEqualTo("completed");
        assertThat(response.finishedAt()).isNotNull();
        assertThat(response.documentResults().get(0).status()).isEqualTo("completed");
        assertThat(response.documentResults().get(0).currentStage()).isEqualTo("wiki_applied");
        assertThat(response.documentResults().get(0).summary()).isEqualTo("휴가 규정을 Wiki에 반영했습니다.");
        assertThat(response.documentResults().get(0).failureReason()).isNull();
        assertThat(response.documentResults().get(1).status()).isEqualTo("failed");
        assertThat(response.documentResults().get(1).summary()).isNull();
        assertThat(response.documentResults().get(1).failureReason()).isEqualTo("Wiki 변환에 실패했습니다.");
        assertThat(response.documentResults().get(1).failureStage()).isEqualTo("agent_timeout");
        // currentStage 는 문서 상태에서 역산해 실패한 문서를 전부 parsing 으로 만든다.
        // 이 문서는 파싱을 끝내고 Wiki 변환에서 죽었으므로 두 값이 갈린다 — 화면은
        // failureStage 를 보여야 한다.
        assertThat(response.documentResults().get(1).currentStage()).isEqualTo("parsing");
    }

    @Test
    @DisplayName("문서가 삭제돼도 기록된 파일명이 이력에 남는다")
    void keepsTheFileNameAfterTheDocumentIsDeleted() throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(15L));
        assign(job, "id", 42L);
        job.start();
        job.finish(List.of(AiJob.DocumentParseResult.succeeded(
                15L, "2024_인사규정_최종.pdf", "인사규정을 Wiki에 반영했습니다.")));
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job));
        // 문서는 하드 삭제됐다(DR-014) — 조회에서 빠진다.
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of());

        AiJobResponse response = service.getAiJob(42L);

        assertThat(response.documentResults()).singleElement()
                .satisfies(result -> {
                    assertThat(result.originalFileName()).isEqualTo("2024_인사규정_최종.pdf");
                    assertThat(result.summary()).isEqualTo("인사규정을 Wiki에 반영했습니다.");
                });
    }

    @Test
    @DisplayName("기록된 파일명이 살아 있는 문서의 현재 이름보다 우선한다")
    void prefersTheRecordedSnapshotOverTheCurrentName() throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(15L));
        assign(job, "id", 42L);
        job.start();
        job.finish(List.of(AiJob.DocumentParseResult.succeeded(15L, "옛이름.pdf", "반영했습니다.")));
        Document renamed = completedDocument(15L, "새이름.pdf");
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job));
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of(renamed));

        AiJobResponse response = service.getAiJob(42L);

        // 이력은 그때를 가리켜야 한다 — 파일이 교체돼도 그 작업이 처리한 것은 옛 파일이다.
        assertThat(response.documentResults().get(0).originalFileName()).isEqualTo("옛이름.pdf");
    }

    @Test
    @DisplayName("스냅샷이 없는 옛 작업은 살아 있는 문서의 현재 이름으로 메운다")
    void fallsBackToTheLiveNameForOldRecords() throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(15L));
        assign(job, "id", 42L);
        job.start();
        // 이 필드가 생기기 전에 저장된 결과 (S15P11B106-202 이전)
        job.finish(List.of(AiJob.DocumentParseResult.succeeded(15L, null, "반영했습니다.")));
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job));
        given(documentRepository.findAllById(List.of(15L)))
                .willReturn(List.of(completedDocument(15L, "살아있는이름.pdf")));

        AiJobResponse response = service.getAiJob(42L);

        assertThat(response.documentResults().get(0).originalFileName()).isEqualTo("살아있는이름.pdf");
    }

    @Test
    @DisplayName("실패 단계를 모르면 비워 둔다")
    void leavesFailureStageEmptyWhenUnknown() throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(15L));
        assign(job, "id", 42L);
        job.start();
        Document processing = uploadedDocument(15L, "first.md");
        processing.startParsing();
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job));
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of(processing));

        AiJobResponse response = service.getAiJob(42L);

        assertThat(response.documentResults().get(0).failureStage()).isNull();
    }

    @Test
    @DisplayName("진행 중인 작업은 아직 기록된 결과가 없어 요약을 비워 둔다")
    void leavesSummaryEmptyWhileProcessing() throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(15L));
        assign(job, "id", 42L);
        job.start();
        Document processing = uploadedDocument(15L, "first.md");
        processing.startParsing();
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job));
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of(processing));

        AiJobResponse response = service.getAiJob(42L);

        assertThat(response.documentResults()).singleElement()
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo("processing");
                    assertThat(result.summary()).isNull();
                    assertThat(result.failureReason()).isNull();
                });
    }

    @Test
    @DisplayName("작업 이력 목록은 최신순으로 문서별 요약까지 함께 돌려준다")
    void listsAiJobs() throws Exception {
        AiJob recent = finishedJob(42L, "2026-07-26T15:24:00", 15L, "인사규정을 Wiki에 반영했습니다.");
        AiJob older = finishedJob(41L, "2026-07-24T10:02:00", 16L, "영업전략을 Wiki에 반영했습니다.");
        Document first = completedDocument(15L, "2024_인사규정_최종.pdf");
        Document second = completedDocument(16L, "Q3_영업전략.pdf");
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(recent, older), PageRequest.of(0, 20), 2));
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(first, second));

        AiJobListResponse response = service.listAiJobs(null, null);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalCount()).isEqualTo(2);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.items()).extracting(AiJobResponse::jobId).containsExactly("42", "41");
        assertThat(response.items().get(0).documentResults()).singleElement()
                .satisfies(result -> {
                    assertThat(result.documentId()).isEqualTo("15");
                    assertThat(result.summary()).isEqualTo("인사규정을 Wiki에 반영했습니다.");
                    assertThat(result.status()).isEqualTo("completed");
                });
        assertThat(response.items().get(1).documentResults().get(0).summary())
                .isEqualTo("영업전략을 Wiki에 반영했습니다.");
    }

    @Test
    @DisplayName("목록의 문서는 페이지 전체를 한 번에 읽는다")
    void loadsDocumentsOncePerPage() throws Exception {
        AiJob one = finishedJob(42L, "2026-07-26T15:24:00", 15L, "요약 하나");
        AiJob two = finishedJob(41L, "2026-07-24T10:02:00", 16L, "요약 둘");
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(one, two), PageRequest.of(0, 20), 2));
        given(documentRepository.findAllById(List.of(15L, 16L)))
                .willReturn(List.of(completedDocument(15L, "a.pdf"), completedDocument(16L, "b.pdf")));

        service.listAiJobs(null, null);

        // 작업마다 조회하면 페이지 크기만큼 질의가 늘어난다.
        verify(documentRepository).findAllById(List.of(15L, 16L));
        verifyNoMoreInteractions(documentRepository);
    }

    @Test
    @DisplayName("작업이 하나도 없으면 빈 배열을 돌려준다")
    void listsNothingWhenNoJobs() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        AiJobListResponse response = service.listAiJobs(null, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.totalCount()).isZero();
    }

    @Test
    @DisplayName("잘못된 page·size 는 400 오류로 처리한다")
    void rejectsBadPaging() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));

        assertThatThrownBy(() -> service.listAiJobs(0, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> service.listAiJobs(null, 101))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("관리자가 아니면 작업 이력 목록도 조회할 수 없다")
    void rejectsNonAdminOnList() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(20L, CurrentMemberRole.EMPLOYEE));

        assertThatThrownBy(() -> service.listAiJobs(null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
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

    private AiJob finishedJob(long jobId, String createdAt, long documentId, String summary) throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/%d".formatted(jobId), List.of(documentId));
        assign(job, "id", jobId);
        assign(job, "createdAt", LocalDateTime.parse(createdAt));
        job.start();
        job.finish(List.of(AiJob.DocumentParseResult.succeeded(documentId, "문서.pdf", summary)));
        return job;
    }

    // ===== 문서 행이 사라진 결과의 표시(S15P11B106-209) =====

    @Test
    @DisplayName("삭제가 성공해 문서 행이 사라진 결과는 완료로 보이고 걷어낸 요약이 읽힌다")
    void missingDocumentWithSucceededResult() throws Exception {
        AiJob job = finishedJob(50L, 30L, AiJob.DocumentParseResult.succeeded(
                30L, "노트북 보관 및 반출_예시.docx", "노트북 반출 규정 항목을 걷어냈습니다."));
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(50L)).willReturn(Optional.of(job));
        // 걷어내기가 끝나 행이 지워졌다(S15P11B106-195).
        given(documentRepository.findAllById(List.of(30L))).willReturn(List.of());

        AiJobResponse.DocumentResultResponse result = service.getAiJob(50L).documentResults().getFirst();

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.summary()).isEqualTo("노트북 반출 규정 항목을 걷어냈습니다.");
        assertThat(result.failureReason()).isNull();
        assertThat(result.originalFileName()).isEqualTo("노트북 보관 및 반출_예시.docx");
    }

    @Test
    @DisplayName("문서 행이 사라진 실패 결과는 기록된 실제 사유를 보여준다")
    void missingDocumentWithFailedResult() throws Exception {
        AiJob job = finishedJob(51L, 31L, AiJob.DocumentParseResult.failed(
                31L, "규정.docx", "FastAPI 응답에 걷어낼 변경이 없습니다.", "wiki_transform"));
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(51L)).willReturn(Optional.of(job));
        given(documentRepository.findAllById(List.of(31L))).willReturn(List.of());

        AiJobResponse.DocumentResultResponse result = service.getAiJob(51L).documentResults().getFirst();

        assertThat(result.status()).isEqualTo("failed");
        // 예전에는 「문서를 찾을 수 없습니다」가 실제 사유를 덮었다.
        assertThat(result.failureReason()).isEqualTo("FastAPI 응답에 걷어낼 변경이 없습니다.");
        assertThat(result.failureStage()).isEqualTo("wiki_transform");
    }

    @Test
    @DisplayName("문서도 기록도 없는 옛 작업은 문서를 찾을 수 없다고 남는다")
    void missingDocumentWithoutRecordedResult() throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/52", List.of(32L));
        assign(job, "id", 52L);
        assign(job, "createdAt", LocalDateTime.parse("2026-08-03T15:32:00"));
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(52L)).willReturn(Optional.of(job));
        given(documentRepository.findAllById(List.of(32L))).willReturn(List.of());

        AiJobResponse.DocumentResultResponse result = service.getAiJob(52L).documentResults().getFirst();

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.failureReason()).isEqualTo("문서를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("끝난 작업은 문서가 살아 있어도 그때 기록한 사유를 보여준다")
    void settledJobKeepsRecordedFailureReason() throws Exception {
        AiJob job = finishedJob(53L, 33L, AiJob.DocumentParseResult.failed(
                33L, "규정.docx", "기록된 사유", "wiki_transform"));
        Document document = uploadedDocument(33L, "규정.docx");
        document.startParsing();
        document.failParsing("문서의 현재 사유");
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(53L)).willReturn(Optional.of(job));
        given(documentRepository.findAllById(List.of(33L))).willReturn(List.of(document));

        AiJobResponse.DocumentResultResponse result = service.getAiJob(53L).documentResults().getFirst();

        // 문서의 현재 사유를 얹으면 나중에 난 오류가 지난 회차 이력에 붙는다.
        assertThat(result.failureReason()).isEqualTo("기록된 사유");
    }

    @Test
    @DisplayName("끝난 작업의 상태는 문서의 현재 상태를 따라가지 않는다")
    void settledJobKeepsRecordedStatus() throws Exception {
        // 같은 문서를 다시 지우는 중이면, 예전에 성공한 작업까지 「처리 중」으로 바뀌던 증상이다.
        AiJob job = finishedJob(54L, 34L, AiJob.DocumentParseResult.succeeded(
                34L, "규정.docx", "휴가 규정 Wiki를 만들었습니다."));
        Document document = completedDocument(34L, "규정.docx");
        document.markForDeletion();
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(54L)).willReturn(Optional.of(job));
        given(documentRepository.findAllById(List.of(34L))).willReturn(List.of(document));

        AiJobResponse.DocumentResultResponse result = service.getAiJob(54L).documentResults().getFirst();

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.currentStage()).isEqualTo("wiki_applied");
    }

    @Test
    @DisplayName("진행 중인 작업은 문서의 현재 상태로 진행 단계를 보여준다")
    void runningJobFollowsDocumentStatus() throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/55", List.of(35L));
        assign(job, "id", 55L);
        assign(job, "createdAt", LocalDateTime.parse("2026-08-03T15:32:00"));
        job.start();
        Document document = uploadedDocument(35L, "규정.docx");
        document.startParsing();
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(aiJobRepository.findById(55L)).willReturn(Optional.of(job));
        given(documentRepository.findAllById(List.of(35L))).willReturn(List.of(document));

        AiJobResponse.DocumentResultResponse result = service.getAiJob(55L).documentResults().getFirst();

        assertThat(result.status()).isEqualTo("processing");
        assertThat(result.currentStage()).isEqualTo("parsing");
    }

    private AiJob finishedJob(long jobId, long documentId, AiJob.DocumentParseResult result) throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/%d".formatted(jobId), List.of(documentId));
        assign(job, "id", jobId);
        assign(job, "createdAt", LocalDateTime.parse("2026-08-03T15:32:00"));
        job.start();
        job.finish(List.of(result));
        return job;
    }

    private Document completedDocument(long id, String fileName) throws Exception {
        Document document = uploadedDocument(id, fileName);
        document.startParsing();
        document.completeParsing("wiki/ALL/sources/%d/parsed.md".formatted(id));
        document.completeProcessing(List.of(101L));
        return document;
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
