package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.document.api.DocumentDeleteResponse;
import com.ajt.backend.domain.document.api.DocumentDetailResponse;
import com.ajt.backend.domain.document.api.DocumentFileReplaceResponse;
import com.ajt.backend.domain.document.api.DocumentListResponse;
import com.ajt.backend.domain.document.api.DocumentMetadataUpdateRequest;
import com.ajt.backend.domain.document.api.DocumentRetryResponse;
import com.ajt.backend.domain.document.api.DocumentSummaryResponse;
import com.ajt.backend.domain.document.api.DocumentUpdateResponse;
import com.ajt.backend.domain.document.api.DocumentUploadValidationException;
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
import com.ajt.backend.domain.document.storage.DocumentFileMutation;
import com.ajt.backend.domain.document.storage.StagedOriginalFile;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.global.ai.client.WikiDocumentChangeType;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.lang.reflect.Field;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;

@DisplayName("원본문서 관리 서비스")
class DocumentManagementServiceTest {

    private final CurrentMemberProvider currentMemberProvider = mock(CurrentMemberProvider.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentCategoryRepository documentCategoryRepository = mock(DocumentCategoryRepository.class);
    private final AiJobRepository aiJobRepository = mock(AiJobRepository.class);
    private final DocumentParseJobLauncher parseJobLauncher = mock(DocumentParseJobLauncher.class);
    private final DocumentFileStorage documentFileStorage = mock(DocumentFileStorage.class);
    private final DocumentFileMutation documentFileMutation = mock(DocumentFileMutation.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final WikiScopeRepository wikiScopeRepository = mock(WikiScopeRepository.class);
    private final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
    private final AiJobFailureMarker aiJobFailureMarker = mock(AiJobFailureMarker.class);
    private final DocumentFailureMarker documentFailureMarker = mock(DocumentFailureMarker.class);
    private final DocumentManagementService service = new DocumentManagementService(
            currentMemberProvider,
            documentRepository,
            documentCategoryRepository,
            aiJobRepository,
            parseJobLauncher,
            documentFileStorage,
            memberRepository,
            wikiScopeRepository,
            departmentRepository,
            aiJobFailureMarker,
            documentFailureMarker
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
        assertThat(response.documentCategoryId()).isEqualTo("7");
        assertThat(response.documentCategoryName()).isEqualTo("취업규칙");
        assertThat(response.visibilityType()).isEqualTo("all");
        assertThat(response.departments()).isEmpty();
        assertThat(response.downloadUrl()).isEqualTo("/api/v1/documents/15/file");
        assertThat(response.relatedWikis()).isEmpty();
        assertThat(response.uploadedAt()).isEqualTo(Instant.parse("2026-07-28T05:00:00Z"));
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
    @DisplayName("FAILED 상태 문서는 다운로드를 INVALID_DOCUMENT_STATUS로 거절한다(S15P11B106-146)")
    void downloadFileRejectsFailedDocument() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        document.failReplace("파일 교체 확정(staging→최종 이동) 실패로 문서 처리에 실패했습니다."); // FAILED로 전환
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> service.downloadFile(15L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_DOCUMENT_STATUS);
        // 상태 확인이 파일 로드보다 앞서므로 저장소 접근 자체를 하지 않는다.
        verify(documentFileStorage, never()).load(any());
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
        verify(parseJobLauncher).launch(any(AiJob.class), any(DocumentReprocessPlan.class));
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
    @DisplayName("파일 교체 시 새 파일을 저장하고 파일 메타를 갱신한 뒤 재처리 작업을 생성한다")
    void replaceFileStoresNewFileAndReprocesses() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        // 같은 확장자(.md)라 최종 경로는 그대로 → 커밋 후 promote가 덮어쓰고 별도 원본 삭제는 불필요
        given(documentFileStorage.stageOriginal(any(), anyLong(), any()))
                .willReturn(new StagedOriginalFile(
                        "wiki/ALL/sources/15/.staging/new.md", "wiki/ALL/sources/15/original.md"));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        DocumentFileReplaceResponse response = service.replaceFile(15L,
                new MockMultipartFile("file", "updated.md", "text/markdown", "# 새 내용".getBytes()));

        assertThat(response.jobId()).isEqualTo("42");
        assertThat(response.documentId()).isEqualTo("15");
        assertThat(response.status()).isEqualTo("waiting");
        assertThat(document.originalFileName()).isEqualTo("updated.md");
        assertThat(document.status().name()).isEqualTo("UPLOADED");
        verify(documentFileStorage).stageOriginal(any(), anyLong(), any());
        // 트랜잭션 밖 단위테스트라 즉시 확정 경로: staging→최종 이동이 일어난다.
        verify(documentFileStorage).promoteStagedOriginal(
                "wiki/ALL/sources/15/.staging/new.md", "wiki/ALL/sources/15/original.md");
        // 파싱 전 문서(parsedPath null)+같은 경로라 삭제할 기존 파일이 없다.
        verify(documentFileStorage, never()).delete(any());
        // promote 성공 후 즉시 재처리가 실행된다(afterCommit 안에서 launchNow).
        verify(parseJobLauncher).launchNow(any(AiJob.class), any(DocumentReprocessPlan.class));
    }

    @Test
    @DisplayName("파일 교체는 덮어쓰기 전에 읽은 파싱 본문과 함께 document_replaced로 재처리한다")
    void replaceFileSendsReplacedChangeTypeWithPreviousParsedMarkdown() throws Exception {
        Document document = parsedDocument(); // parsedPath 있음
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 옛 취업규칙\n본문");
        given(documentFileStorage.stageOriginal(any(), anyLong(), any()))
                .willReturn(new StagedOriginalFile(
                        "wiki/ALL/sources/15/.staging/new.md", "wiki/ALL/sources/15/original.md"));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        service.replaceFile(15L,
                new MockMultipartFile("file", "updated.md", "text/markdown", "# 새 내용".getBytes()));

        ArgumentCaptor<DocumentReprocessPlan> plans = ArgumentCaptor.forClass(DocumentReprocessPlan.class);
        verify(parseJobLauncher).launchNow(any(AiJob.class), plans.capture());
        assertThat(plans.getValue().changeTypeOf(15L)).isEqualTo(WikiDocumentChangeType.DOCUMENT_REPLACED);
        assertThat(plans.getValue().removedParsedMarkdownOf(15L)).isEqualTo("# 옛 취업규칙\n본문");

        // 파일을 staging에 저장하기 전에 옛 파싱 본문을 읽어야 한다 — 커밋 후 parsed는 정리·재생성된다.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(documentFileStorage);
        order.verify(documentFileStorage).readText("wiki/ALL/sources/15/parsed.md");
        order.verify(documentFileStorage).stageOriginal(any(), anyLong(), any());
    }

    @Test
    @DisplayName("파일 교체 시 옛 파싱 본문이 없으면 document_added로 강등한다")
    void replaceFileFallsBackToAddedWhenParsedMarkdownIsMissing() throws Exception {
        Document document = uploadedDocument(); // parsedPath 없음(파싱 전 문서)
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentFileStorage.stageOriginal(any(), anyLong(), any()))
                .willReturn(new StagedOriginalFile(
                        "wiki/ALL/sources/15/.staging/new.md", "wiki/ALL/sources/15/original.md"));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        service.replaceFile(15L,
                new MockMultipartFile("file", "updated.md", "text/markdown", "# 새 내용".getBytes()));

        ArgumentCaptor<DocumentReprocessPlan> plans = ArgumentCaptor.forClass(DocumentReprocessPlan.class);
        verify(parseJobLauncher).launchNow(any(AiJob.class), plans.capture());
        assertThat(plans.getValue().changeTypeOf(15L)).isEqualTo(WikiDocumentChangeType.DOCUMENT_ADDED);
        assertThat(plans.getValue().removedParsedMarkdownOf(15L)).isNull();
    }

    @Test
    @DisplayName("파일 교체로 확장자가 바뀌어 경로가 달라지면 이전 파일을 정리한다")
    void replaceFileDeletesPreviousFileWhenPathChanges() throws Exception {
        Document document = uploadedDocument(); // 기존 경로: wiki/ALL/sources/15/original.md
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentFileStorage.stageOriginal(any(), anyLong(), any()))
                .willReturn(new StagedOriginalFile(
                        "wiki/ALL/sources/15/.staging/new.pdf",
                        "wiki/ALL/sources/15/original.pdf")); // 확장자 변경 → 최종 경로가 달라짐
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        service.replaceFile(15L,
                new MockMultipartFile("file", "updated.pdf", "application/pdf", "%PDF-1.4".getBytes()));

        assertThat(document.originalPath()).isEqualTo("wiki/ALL/sources/15/original.pdf");
        // 커밋 후: staging→최종(.pdf) 확정 + 경로가 달라진 기존 원본(.md) 삭제
        verify(documentFileStorage).promoteStagedOriginal(
                "wiki/ALL/sources/15/.staging/new.pdf", "wiki/ALL/sources/15/original.pdf");
        verify(documentFileStorage).delete("wiki/ALL/sources/15/original.md");
    }

    @Test
    @DisplayName("교체 중 재처리 작업 생성이 실패하면 기존 파일을 지우지 않고 임시 파일만 정리한다(S15P11B106-146)")
    void replaceDoesNotTouchOldFilesWhenReprocessJobCreationFails() throws Exception {
        Document document = parsedDocument(); // 기존 원본 original.md + 파싱 parsed.md
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 옛 본문");
        given(documentFileStorage.stageOriginal(any(), anyLong(), any()))
                .willReturn(new StagedOriginalFile(
                        "wiki/ALL/sources/15/.staging/new.md", "wiki/ALL/sources/15/original.md"));
        // 재처리 작업 저장(=DB 작업) 단계에서 예외 → 트랜잭션 롤백
        given(aiJobRepository.save(any(AiJob.class))).willThrow(new IllegalStateException("작업 저장 실패"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.replaceFile(15L,
                    new MockMultipartFile("file", "updated.md", "text/markdown", "# 새 내용".getBytes())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("작업 저장 실패");
            // 롤백 시뮬레이션
            fireAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            // 확정(promote)·기존 파일 삭제는 일어나지 않고, 새로 저장한 임시 파일만 정리된다.
            verify(documentFileStorage, never()).promoteStagedOriginal(anyString(), anyString());
            verify(documentFileStorage, never()).delete("wiki/ALL/sources/15/original.md");
            verify(documentFileStorage, never()).delete("wiki/ALL/sources/15/parsed.md");
            verify(documentFileStorage).delete("wiki/ALL/sources/15/.staging/new.md");
            // 재처리도 시작되지 않는다.
            verify(parseJobLauncher, never()).launchNow(any(AiJob.class), any(DocumentReprocessPlan.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("교체 확정(promote)이 커밋 후 실패하면 재처리를 시작하지 않고 작업을 FAILED로 남긴다(S15P11B106-146)")
    void replacePromoteFailureMarksJobFailedAndSkipsReprocess() throws Exception {
        Document document = parsedDocument(); // 기존 original.md + parsed.md
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 옛 본문");
        given(documentFileStorage.stageOriginal(any(), anyLong(), any()))
                .willReturn(new StagedOriginalFile(
                        "wiki/ALL/sources/15/.staging/new.pdf", "wiki/ALL/sources/15/original.pdf"));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });
        // 커밋 후 파일 확정(staging→최종 이동)이 실패한다.
        willThrow(new IOException("이동 실패")).given(documentFileStorage)
                .promoteStagedOriginal(anyString(), anyString());

        TransactionSynchronizationManager.initSynchronization();
        try {
            DocumentFileReplaceResponse response = service.replaceFile(15L,
                    new MockMultipartFile("file", "updated.pdf", "application/pdf", "%PDF-1.4".getBytes()));
            assertThat(response.jobId()).isEqualTo("42");

            // 커밋 후 promote 실패 → 예외가 새어 나오지 않고, 재처리·기존 파일 삭제는 하지 않으며 작업을 FAILED로 남긴다.
            assertThatCode(this::fireAfterCommit).doesNotThrowAnyException();
            verify(parseJobLauncher, never()).launchNow(any(AiJob.class), any(DocumentReprocessPlan.class));
            verify(documentFileStorage, never()).delete("wiki/ALL/sources/15/original.md");
            verify(documentFileStorage, never()).delete("wiki/ALL/sources/15/parsed.md");
            // 작업과 문서 모두 FAILED로 남긴다.
            verify(aiJobFailureMarker).markFailedBeforeStart(eq(42L), anyString());
            verify(documentFailureMarker).markFailed(eq(15L), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("promote 실패 시 AiJob 마킹이 예외를 던져도 Document 마킹은 시도된다(S15P11B106-146)")
    void replacePromoteFailureStillMarksDocumentWhenJobMarkingFails() throws Exception {
        Document document = parsedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 옛 본문");
        given(documentFileStorage.stageOriginal(any(), anyLong(), any()))
                .willReturn(new StagedOriginalFile(
                        "wiki/ALL/sources/15/.staging/new.pdf", "wiki/ALL/sources/15/original.pdf"));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });
        willThrow(new IOException("이동 실패")).given(documentFileStorage)
                .promoteStagedOriginal(anyString(), anyString());
        // AiJob 마킹이 실패해도 Document 마킹은 시도되어야 한다.
        willThrow(new RuntimeException("작업 마킹 실패")).given(aiJobFailureMarker)
                .markFailedBeforeStart(anyLong(), anyString());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.replaceFile(15L,
                    new MockMultipartFile("file", "updated.pdf", "application/pdf", "%PDF-1.4".getBytes()));

            assertThatCode(this::fireAfterCommit).doesNotThrowAnyException();
            verify(aiJobFailureMarker).markFailedBeforeStart(eq(42L), anyString());
            verify(documentFailureMarker).markFailed(eq(15L), anyString()); // 여전히 시도됨
            verify(parseJobLauncher, never()).launchNow(any(AiJob.class), any(DocumentReprocessPlan.class));
            verify(documentFileStorage, never()).delete("wiki/ALL/sources/15/original.md");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("promote 실패 시 Document 마킹이 예외를 던져도 밖으로 새지 않고 재처리·삭제는 안 한다(S15P11B106-146)")
    void replacePromoteFailureSwallowsDocumentMarkingException() throws Exception {
        Document document = parsedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 옛 본문");
        given(documentFileStorage.stageOriginal(any(), anyLong(), any()))
                .willReturn(new StagedOriginalFile(
                        "wiki/ALL/sources/15/.staging/new.pdf", "wiki/ALL/sources/15/original.pdf"));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });
        willThrow(new IOException("이동 실패")).given(documentFileStorage)
                .promoteStagedOriginal(anyString(), anyString());
        willThrow(new RuntimeException("문서 마킹 실패")).given(documentFailureMarker)
                .markFailed(anyLong(), anyString());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.replaceFile(15L,
                    new MockMultipartFile("file", "updated.pdf", "application/pdf", "%PDF-1.4".getBytes()));

            // 문서 마킹 예외가 afterCommit 밖으로 새지 않는다.
            assertThatCode(this::fireAfterCommit).doesNotThrowAnyException();
            verify(parseJobLauncher, never()).launchNow(any(AiJob.class), any(DocumentReprocessPlan.class));
            verify(documentFileStorage, never()).delete("wiki/ALL/sources/15/original.md");
            verify(documentFileStorage, never()).delete("wiki/ALL/sources/15/parsed.md");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("교체는 커밋 전에는 기존 파일을 지우거나 최종 경로를 덮어쓰지 않는다(S15P11B106-146)")
    void replaceDoesNotFinalizeBeforeCommit() throws Exception {
        Document document = parsedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 옛 본문");
        given(documentFileStorage.stageOriginal(any(), anyLong(), any()))
                .willReturn(new StagedOriginalFile(
                        "wiki/ALL/sources/15/.staging/new.pdf", "wiki/ALL/sources/15/original.pdf"));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.replaceFile(15L,
                    new MockMultipartFile("file", "updated.pdf", "application/pdf", "%PDF-1.4".getBytes()));

            // 커밋 전: 최종 경로 확정(promote)·기존 파일 삭제가 아직 일어나지 않는다.
            verify(documentFileStorage, never()).promoteStagedOriginal(anyString(), anyString());
            verify(documentFileStorage, never()).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("교체가 커밋되면 새 파일을 확정하고 기존 원본·파싱 파일을 지운다(S15P11B106-146)")
    void replaceFinalizesAndDeletesOldFilesAfterCommit() throws Exception {
        Document document = parsedDocument(); // 기존 original.md + parsed.md
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 옛 본문");
        // 확장자 변경(.pdf) → 최종 경로가 달라져 기존 원본(.md)도 삭제 대상이 된다.
        given(documentFileStorage.stageOriginal(any(), anyLong(), any()))
                .willReturn(new StagedOriginalFile(
                        "wiki/ALL/sources/15/.staging/new.pdf", "wiki/ALL/sources/15/original.pdf"));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            DocumentFileReplaceResponse response = service.replaceFile(15L,
                    new MockMultipartFile("file", "updated.pdf", "application/pdf", "%PDF-1.4".getBytes()));

            verify(documentFileStorage, never()).promoteStagedOriginal(anyString(), anyString());
            fireAfterCommit(); // 커밋 성공 시뮬레이션

            verify(documentFileStorage).promoteStagedOriginal(
                    "wiki/ALL/sources/15/.staging/new.pdf", "wiki/ALL/sources/15/original.pdf");
            verify(documentFileStorage).delete("wiki/ALL/sources/15/original.md");
            verify(documentFileStorage).delete("wiki/ALL/sources/15/parsed.md");
            assertThat(response.jobId()).isEqualTo("42");
            verify(parseJobLauncher).launchNow(any(AiJob.class), any(DocumentReprocessPlan.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("파싱 파일이 없는 문서 교체는 원본만 처리하고 null 경로 삭제를 시도하지 않는다(S15P11B106-146)")
    void replaceWithNullParsedPathHandlesOnlyOriginal() throws Exception {
        Document document = uploadedDocument(); // parsedPath null(파싱 전)
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        // 확장자 변경 → 기존 원본(.md)만 삭제, 파싱 파일은 없으므로 삭제 시도 없음
        given(documentFileStorage.stageOriginal(any(), anyLong(), any()))
                .willReturn(new StagedOriginalFile(
                        "wiki/ALL/sources/15/.staging/new.pdf", "wiki/ALL/sources/15/original.pdf"));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.replaceFile(15L,
                    new MockMultipartFile("file", "updated.pdf", "application/pdf", "%PDF-1.4".getBytes()));
            fireAfterCommit();

            verify(documentFileStorage).promoteStagedOriginal(
                    "wiki/ALL/sources/15/.staging/new.pdf", "wiki/ALL/sources/15/original.pdf");
            // 기존 원본만 삭제, parsed 경로 삭제는 시도조차 하지 않는다(정확히 1회 = 원본만).
            verify(documentFileStorage).delete("wiki/ALL/sources/15/original.md");
            verify(documentFileStorage, times(1)).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("커밋 후 기존 파일 삭제가 IOException으로 실패해도 교체 결과를 뒤집지 않는다(S15P11B106-146)")
    void replaceFileDeletionFailureDoesNotBreakCommittedReplace() throws Exception {
        Document document = parsedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 옛 본문");
        given(documentFileStorage.stageOriginal(any(), anyLong(), any()))
                .willReturn(new StagedOriginalFile(
                        "wiki/ALL/sources/15/.staging/new.pdf", "wiki/ALL/sources/15/original.pdf"));
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });
        willThrow(new IOException("파일 삭제 실패")).given(documentFileStorage).delete(anyString());

        TransactionSynchronizationManager.initSynchronization();
        try {
            DocumentFileReplaceResponse response = service.replaceFile(15L,
                    new MockMultipartFile("file", "updated.pdf", "application/pdf", "%PDF-1.4".getBytes()));
            assertThat(response.jobId()).isEqualTo("42");

            // 커밋 후 기존 파일 삭제가 실패해도 예외가 새어 나가지 않는다(경고 로그만).
            assertThatCode(this::fireAfterCommit).doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("허용되지 않는 형식의 파일로 교체하면 업로드 검증 오류를 반환한다")
    void rejectsReplaceForInvalidFile() {
        Document document = uploadedDocument();
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> service.replaceFile(15L,
                new MockMultipartFile("file", "bad.exe", "application/octet-stream", "x".getBytes())))
                .isInstanceOf(DocumentUploadValidationException.class);
    }

    @Test
    @DisplayName("처리 중인 문서는 파일을 교체할 수 없다")
    void rejectsReplaceWhenInProgress() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        document.startParsing(); // PARSING 상태로 전환
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> service.replaceFile(15L,
                new MockMultipartFile("file", "updated.md", "text/markdown", "# 새 내용".getBytes())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_DOCUMENT_STATUS);
    }

    @Test
    @DisplayName("관리자가 아니면 파일을 교체할 수 없다")
    void rejectsNonAdminReplace() {
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.EMPLOYEE));

        assertThatThrownBy(() -> service.replaceFile(15L,
                new MockMultipartFile("file", "updated.md", "text/markdown", "# 새 내용".getBytes())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
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
        assertThat(item.documentCategoryName()).isEqualTo("취업규칙");
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
        assertThat(response.document().documentCategoryName()).isEqualTo("취업규칙");
        assertThat(document.status().name()).isEqualTo("UPLOADED");
        verify(parseJobLauncher).launch(any(AiJob.class), any(DocumentReprocessPlan.class));
        // 같은 범위 수정은 이 문서만 증분 재처리한다(범위 전체를 훑지 않는다).
        verify(documentRepository, never()).findByScopeKey(any());
    }

    @Test
    @DisplayName("공개 범위가 바뀌면 기존 범위는 걷어내고 새 범위에 이 문서만 반영한다")
    void updatesDocumentMetadataWithScopeChange() throws Exception {
        Document document = parsedDocument(); // scope "ALL", parsedPath 있음
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentCategoryRepository.findById(4L)).willReturn(Optional.of(category(4L, "D1-D3", "사규")));
        given(wikiScopeRepository.findById("D1-D3")).willReturn(Optional.of(mock(WikiScope.class)));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 옛 취업규칙\n본문");
        given(documentFileStorage.moveToScope(anyString(), any(), eq("D1-D3"), eq(15L))).willReturn(documentFileMutation);
        given(documentFileMutation.originalPath()).willReturn("wiki/D1-D3/sources/15/original.md");
        given(documentFileMutation.parsedPath()).willReturn("wiki/D1-D3/sources/15/parsed.md");
        given(documentRepository.findByScopeKey("ALL")).willReturn(List.of(document));
        given(documentRepository.findByScopeKey("D1-D3")).willReturn(List.of());
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        DocumentUpdateResponse response = service.update(
                15L, new DocumentMetadataUpdateRequest(4L, "department", List.of(1L, 3L)));

        assertThat(response.jobId()).isEqualTo("42");
        assertThat(response.document().scopeKey()).isEqualTo("D1-D3");
        assertThat(response.document().documentCategoryName()).isEqualTo("사규");
        assertThat(document.scopeKey()).isEqualTo("D1-D3");
        assertThat(document.status().name()).isEqualTo("UPLOADED");
        assertThat(response.reprocessJobs()).hasSize(2);

        // 기존 범위 걷어내기 + 새 범위 반영 → 재처리 작업 2건. 범위 전체를 다시 훑지 않는다.
        ArgumentCaptor<AiJob> jobs = ArgumentCaptor.forClass(AiJob.class);
        ArgumentCaptor<DocumentReprocessPlan> plans = ArgumentCaptor.forClass(DocumentReprocessPlan.class);
        verify(parseJobLauncher, times(2)).launch(jobs.capture(), plans.capture());

        AiJob removeJob = jobs.getAllValues().get(0);
        DocumentReprocessPlan removePlan = plans.getAllValues().get(0);
        assertThat(removeJob.scopeKey()).isEqualTo("ALL");
        assertThat(removeJob.documentIds()).containsExactly(15L);
        assertThat(removePlan.changeTypeOf(15L)).isEqualTo(WikiDocumentChangeType.DOCUMENT_REMOVED);
        // 파일을 옮기기 전에 읽은 본문이어야 한다 — 이동 뒤 옛 경로에서는 읽을 수 없다.
        assertThat(removePlan.removedParsedMarkdownOf(15L)).isEqualTo("# 옛 취업규칙\n본문");

        AiJob addJob = jobs.getAllValues().get(1);
        assertThat(addJob.scopeKey()).isEqualTo("D1-D3");
        assertThat(addJob.documentIds()).containsExactly(15L);
        assertThat(plans.getAllValues().get(1).changeTypeOf(15L))
                .isEqualTo(WikiDocumentChangeType.DOCUMENT_ADDED);
    }

    @Test
    @DisplayName("공개 범위 변경 시 옛 파싱 본문이 없으면 걷어내기를 건너뛰고 새 범위만 반영한다")
    void skipsRemovalWhenParsedMarkdownIsMissing() throws Exception {
        Document document = uploadedDocument(); // parsedPath 없음(파싱 전)
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentCategoryRepository.findById(4L)).willReturn(Optional.of(category(4L, "D1-D3", "사규")));
        given(wikiScopeRepository.findById("D1-D3")).willReturn(Optional.of(mock(WikiScope.class)));
        given(documentFileStorage.moveToScope(anyString(), any(), eq("D1-D3"), eq(15L))).willReturn(documentFileMutation);
        given(documentFileMutation.originalPath()).willReturn("wiki/D1-D3/sources/15/original.md");
        given(documentFileMutation.parsedPath()).willReturn(null);
        given(documentRepository.findByScopeKey("ALL")).willReturn(List.of(document));
        given(documentRepository.findByScopeKey("D1-D3")).willReturn(List.of());
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        DocumentUpdateResponse response = service.update(
                15L, new DocumentMetadataUpdateRequest(4L, "department", List.of(1L, 3L)));

        assertThat(response.reprocessJobs()).hasSize(1);
        assertThat(response.reprocessJobs().get(0).scopeKey()).isEqualTo("D1-D3");

        ArgumentCaptor<DocumentReprocessPlan> plans = ArgumentCaptor.forClass(DocumentReprocessPlan.class);
        verify(parseJobLauncher, times(1)).launch(any(AiJob.class), plans.capture());
        assertThat(plans.getValue().changeTypeOf(15L)).isEqualTo(WikiDocumentChangeType.DOCUMENT_ADDED);
    }

    @Test
    @DisplayName("공개 범위 변경은 새 범위에 처리 중 문서가 있으면 막는다")
    void rejectsScopeChangeWhenNewScopeIsProcessing() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        Document processing = uploadedDocument();
        assignId(processing, 16L);
        processing.startParsing();
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentCategoryRepository.findById(4L)).willReturn(Optional.of(category(4L, "D1-D3", "사규")));
        given(wikiScopeRepository.findById("D1-D3")).willReturn(Optional.of(mock(WikiScope.class)));
        given(documentRepository.findByScopeKey("ALL")).willReturn(List.of(document));
        given(documentRepository.findByScopeKey("D1-D3")).willReturn(List.of(processing));

        assertThatThrownBy(() -> service.update(
                15L, new DocumentMetadataUpdateRequest(4L, "department", List.of(1L, 3L))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_EDIT_IN_PROGRESS);

        verify(documentFileStorage, never()).moveToScope(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("공개 범위 이동 뒤 작업 생성이 실패하면 파일 이동을 되돌린다")
    void rollsBackFilesWhenScopeReprocessCreationFails() throws Exception {
        Document document = uploadedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentCategoryRepository.findById(4L)).willReturn(Optional.of(category(4L, "D1-D3", "사규")));
        given(wikiScopeRepository.findById("D1-D3")).willReturn(Optional.of(mock(WikiScope.class)));
        given(documentRepository.findByScopeKey("ALL")).willReturn(List.of(document), List.of());
        given(documentRepository.findByScopeKey("D1-D3")).willReturn(List.of(), List.of(document));
        given(documentFileStorage.moveToScope(anyString(), any(), eq("D1-D3"), eq(15L))).willReturn(documentFileMutation);
        given(documentFileMutation.originalPath()).willReturn("wiki/D1-D3/sources/15/original.md");
        given(documentFileMutation.parsedPath()).willReturn(null);
        given(aiJobRepository.save(any(AiJob.class))).willThrow(new IllegalStateException("작업 저장 실패"));

        assertThatThrownBy(() -> service.update(
                15L, new DocumentMetadataUpdateRequest(4L, "department", List.of(1L, 3L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("작업 저장 실패");

        verify(documentFileMutation).rollback();
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
    @DisplayName("관리자는 문서를 삭제하고 이 문서를 근거로 쓴 Wiki를 걷어낸다")
    void deletesDocument() throws Exception {
        Document document = parsedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentRepository.findByScopeKey("ALL")).willReturn(List.of(document));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 취업규칙\n본문");
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        DocumentDeleteResponse response = service.delete(15L);

        // 수정(S15P11B106-93): 파싱 본문이 있으면 재처리 작업이 생성되어 reprocessRequired=true·waiting·jobId.
        assertThat(response.deleted()).isTrue();
        assertThat(response.reprocessRequired()).isTrue();
        assertThat(response.jobId()).isEqualTo("42");
        assertThat(response.scopeKey()).isEqualTo("ALL");
        assertThat(response.status()).isEqualTo("waiting");
        verify(documentRepository).delete(document);

        ArgumentCaptor<AiJob> jobs = ArgumentCaptor.forClass(AiJob.class);
        ArgumentCaptor<DocumentReprocessPlan> plans = ArgumentCaptor.forClass(DocumentReprocessPlan.class);
        verify(parseJobLauncher).launch(jobs.capture(), plans.capture());
        assertThat(jobs.getValue().scopeKey()).isEqualTo("ALL");
        assertThat(jobs.getValue().documentIds()).containsExactly(15L);
        assertThat(plans.getValue().changeTypeOf(15L)).isEqualTo(WikiDocumentChangeType.DOCUMENT_REMOVED);
        assertThat(plans.getValue().removedParsedMarkdownOf(15L)).isEqualTo("# 취업규칙\n본문");

        // 파일을 지우기 전에 파싱 본문을 읽어야 한다.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(documentFileStorage);
        order.verify(documentFileStorage).readText("wiki/ALL/sources/15/parsed.md");
        order.verify(documentFileStorage).delete("wiki/ALL/sources/15/original.md");
    }

    @Test
    @DisplayName("파싱 전 문서를 삭제하면 걷어낼 근거가 없어 재처리 작업을 만들지 않는다")
    void deleteSkipsRemovalWhenDocumentWasNeverParsed() throws Exception {
        Document document = uploadedDocument(); // parsedPath 없음
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentRepository.findByScopeKey("ALL")).willReturn(List.of(document));

        DocumentDeleteResponse response = service.delete(15L);

        // 수정(S15P11B106-93): 재처리할 내용이 없으면 삭제는 성공(deleted=true)이되 reprocessRequired=false·
        //   jobId=null·status=skipped로 내려 "jobId=null이 정상 상황"임을 프론트가 구분할 수 있게 한다.
        assertThat(response.deleted()).isTrue();
        assertThat(response.reprocessRequired()).isFalse();
        assertThat(response.jobId()).isNull();
        assertThat(response.status()).isEqualTo("skipped");
        assertThat(response.scopeKey()).isEqualTo("ALL");
        verify(documentRepository).delete(document);
        verify(parseJobLauncher, never()).launch(any(AiJob.class), any(DocumentReprocessPlan.class));
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

    @Test
    @DisplayName("삭제 중 걷어내기 작업 생성이 실패하면 파일 삭제를 예약하지 않는다(S15P11B106-146)")
    void doesNotDeleteFilesWhenReprocessJobCreationFails() throws Exception {
        Document document = parsedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentRepository.findByScopeKey("ALL")).willReturn(List.of(document));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 취업규칙\n본문");
        // 걷어내기 작업 저장(=DB 작업) 단계에서 예외가 나면 트랜잭션이 롤백된다.
        given(aiJobRepository.save(any(AiJob.class))).willThrow(new IllegalStateException("작업 저장 실패"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.delete(15L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("작업 저장 실패");
            // 커밋 성공을 흉내 내도(실제론 롤백된다) 파일 삭제가 예약된 적이 없어 아무 파일도 지워지지 않아야 한다.
            fireAfterCommit();
            verify(documentFileStorage, never()).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("삭제가 커밋된 뒤에야 originalPath와 parsedPath를 지운다(S15P11B106-146)")
    void deletesFilesOnlyAfterCommit() throws Exception {
        Document document = parsedDocument(); // originalPath + parsedPath 모두 존재
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentRepository.findByScopeKey("ALL")).willReturn(List.of(document));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 취업규칙\n본문");
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            DocumentDeleteResponse response = service.delete(15L);
            // 커밋 전에는 파일을 지우지 않는다(경로만 예약).
            verify(documentFileStorage, never()).delete(anyString());

            fireAfterCommit(); // 커밋 성공 시뮬레이션

            verify(documentFileStorage).delete("wiki/ALL/sources/15/original.md");
            verify(documentFileStorage).delete("wiki/ALL/sources/15/parsed.md");
            assertThat(response.deleted()).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("parsedPath가 null인 문서는 커밋 후 originalPath만 지운다(S15P11B106-146)")
    void deletesOnlyOriginalWhenParsedPathIsNull() throws Exception {
        Document document = uploadedDocument(); // parsedPath 없음(파싱 전)
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentRepository.findByScopeKey("ALL")).willReturn(List.of(document));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(15L);
            fireAfterCommit();

            verify(documentFileStorage).delete("wiki/ALL/sources/15/original.md");
            // parsedPath가 null이면 delete는 originalPath 한 번만 호출된다.
            verify(documentFileStorage, times(1)).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("커밋 후 파일 삭제가 IOException으로 실패해도 삭제 API 결과를 뒤집지 않는다(S15P11B106-146)")
    void fileDeletionFailureDoesNotBreakCommittedDelete() throws Exception {
        Document document = parsedDocument();
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findById(15L)).willReturn(Optional.of(document));
        given(documentRepository.findByScopeKey("ALL")).willReturn(List.of(document));
        given(documentFileStorage.readText("wiki/ALL/sources/15/parsed.md")).willReturn("# 취업규칙\n본문");
        given(aiJobRepository.save(any(AiJob.class))).willAnswer(invocation -> {
            AiJob job = invocation.getArgument(0);
            assignId(job, 42L);
            return job;
        });
        willThrow(new IOException("파일 삭제 실패")).given(documentFileStorage).delete(anyString());

        TransactionSynchronizationManager.initSynchronization();
        try {
            // 커밋 결과(응답)는 이미 정상 생성된다.
            DocumentDeleteResponse response = service.delete(15L);
            assertThat(response.deleted()).isTrue();

            // 커밋 이후 파일 삭제가 IOException으로 실패해도 예외가 새어 나가지 않는다(경고 로그만).
            assertThatCode(this::fireAfterCommit).doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 등록된 트랜잭션 동기화들의 afterCommit()을 호출해 커밋 성공을 흉내 낸다. */
    private void fireAfterCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }

    /** 등록된 트랜잭션 동기화들의 afterCompletion(status)을 호출해 커밋/롤백 완료를 흉내 낸다. */
    private void fireAfterCompletion(int status) {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(status);
        }
    }

    @Test
    @DisplayName("부서 공개 문서 목록은 공개유형·부서 이름·업로더 이름을 채워 응답한다")
    void findDocumentsFillsDepartmentAndUploaderNames() throws Exception {
        Document document = Document.uploaded(
                10L, 7L, "D1-D2", "rule.md", "wiki/D1-D2/sources/15/original.md", "text/markdown", 1024L);
        assignId(document, 15L);
        given(currentMemberProvider.currentMember()).willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
        given(documentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(document), PageRequest.of(0, 20), 1));
        given(documentCategoryRepository.findAllById(any())).willReturn(List.of(category(7L, "D1-D2", "사규")));
        Member uploader = mock(Member.class);
        given(uploader.getId()).willReturn(10L);
        given(uploader.getName()).willReturn("김관리");
        given(memberRepository.findAllById(any())).willReturn(List.of(uploader));
        Department dev = mock(Department.class);
        given(dev.getId()).willReturn(1L);
        given(dev.getName()).willReturn("개발부");
        Department plan = mock(Department.class);
        given(plan.getId()).willReturn(2L);
        given(plan.getName()).willReturn("기획부");
        given(departmentRepository.findAllById(any())).willReturn(List.of(dev, plan));

        DocumentListResponse response =
                service.findDocuments(1, 20, "D1-D2", null, null, null, null, null, null, null, null);

        DocumentSummaryResponse item = response.items().get(0);
        assertThat(item.mimeType()).isEqualTo("text/markdown");
        assertThat(item.fileSize()).isEqualTo(1024L);
        assertThat(item.documentCategoryId()).isEqualTo("7");
        assertThat(item.documentCategoryName()).isEqualTo("사규");
        assertThat(item.visibilityType()).isEqualTo("department");
        assertThat(item.departments()).hasSize(2);
        assertThat(item.departments().get(0).departmentId()).isEqualTo("1");
        assertThat(item.departments().get(0).name()).isEqualTo("개발부");
        assertThat(item.departments().get(1).departmentId()).isEqualTo("2");
        assertThat(item.departments().get(1).name()).isEqualTo("기획부");
        assertThat(item.uploadedBy().userId()).isEqualTo("10");
        assertThat(item.uploadedBy().name()).isEqualTo("김관리");
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

    /** 파싱까지 끝난 문서입니다. 걷어내기에 필요한 옛 파싱 본문 경로를 갖습니다. */
    private Document parsedDocument() {
        Document document = uploadedDocument();
        document.startParsing();
        document.completeParsing("wiki/ALL/sources/15/parsed.md", List.of());
        document.completeProcessing(List.of());
        return document;
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
