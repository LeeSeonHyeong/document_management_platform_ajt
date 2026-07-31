package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.wiki.service.WikiTransformationService;
import com.ajt.backend.domain.document.service.DocumentWikiTransformationTransactionService.WikiTransformationResult;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.AiClientFailureType;
import com.ajt.backend.global.ai.client.SourceParseRequest;
import com.ajt.backend.global.ai.client.SourceParseResponse;
import com.ajt.backend.global.ai.client.SourceType;
import com.ajt.backend.global.ai.client.WikiContextSelectionRequest;
import com.ajt.backend.global.ai.client.WikiContextSelectionResponse;
import com.ajt.backend.global.ai.client.WikiDocumentChangeType;
import com.ajt.backend.global.error.FieldErrorResponse;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("원본문서 순차 파싱 워커")
class DocumentParseWorkerTest {

    @Test
    @DisplayName("첫 문서가 끝난 뒤 취소된 작업은 다음 문서 AI 호출을 시작하지 않는다")
    void stopsBeforeNextDocumentWhenCancellationIsObserved() throws Exception {
        Document first = document(15L, "first.md");
        Document second = document(16L, "second.md");
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L, 16L));
        AiJob cancelled = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L, 16L));
        assignId(job, 42L);
        assignId(cancelled, 42L);
        cancelled.start();
        cancelled.cancel();
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(first, second));
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job), Optional.of(cancelled));
        given(fileStorage.load(first.originalPath())).willReturn(resource("first"));
        given(wikiTransformationService.currentIndex("ALL")).willReturn("# 목차");
        given(aiClient.parseSource(any(SourceParseRequest.class))).willReturn(response("15", "# first"));
        given(aiClient.selectWikiContext(any(WikiContextSelectionRequest.class)))
                .willReturn(new WikiContextSelectionResponse(List.of(), "신규 생성 필요"));
        given(fileStorage.storeParsedMarkdown(eq("ALL"), eq(15L), anyString()))
                .willReturn("wiki/ALL/sources/15/parsed.md");
        given(wikiTransformationService.requestForAddedDocument(anyLong(), anyLong(), anyString(), anyString(), anyList()))
                .willReturn(transformationResponse("반영 완료"));
        given(transactionService.applyAddedDocument(anyLong(), anyString(), any()))
                .willReturn(new WikiTransformationResult(List.of(101L), "반영 완료"));

        worker.parse(job);

        org.mockito.Mockito.verify(aiClient).parseSource(org.mockito.ArgumentMatchers.argThat(
                request -> request.sourceId().equals("15")));
        org.mockito.Mockito.verify(aiClient, org.mockito.Mockito.never()).parseSource(org.mockito.ArgumentMatchers.argThat(
                request -> request.sourceId().equals("16")));
        assertThat(second.status()).isEqualTo(DocumentStatus.UPLOADED);
    }

    private final DocumentRepository documentRepository = org.mockito.Mockito.mock(DocumentRepository.class);
    private final AiJobRepository aiJobRepository = org.mockito.Mockito.mock(AiJobRepository.class);
    private final DocumentFileStorage fileStorage = org.mockito.Mockito.mock(DocumentFileStorage.class);
    private final AiClient aiClient = org.mockito.Mockito.mock(AiClient.class);
    private final WikiTransformationService wikiTransformationService =
            org.mockito.Mockito.mock(WikiTransformationService.class);
    private final DocumentWikiTransformationTransactionService transactionService =
            org.mockito.Mockito.mock(DocumentWikiTransformationTransactionService.class);
    private final DocumentParseWorker worker = new DocumentParseWorker(
            documentRepository,
            aiJobRepository,
            fileStorage,
            aiClient,
            wikiTransformationService,
            transactionService
    );

    @Test
    @DisplayName("첫 문서 반영이 실패해도 다음 문서 반영을 계속한다")
    void continuesWhenFirstDocumentApplicationFails() throws Exception {
        Document first = document(15L, "first.md");
        Document second = document(16L, "second.md");
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L, 16L));
        assignId(job, 42L);
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(first, second));
        given(fileStorage.load(first.originalPath())).willReturn(resource("first"));
        given(fileStorage.load(second.originalPath())).willReturn(resource("second"));
        given(wikiTransformationService.currentIndex("ALL")).willReturn("# 목차");
        given(aiClient.parseSource(any(SourceParseRequest.class))).willAnswer(invocation ->
                response(invocation.<SourceParseRequest>getArgument(0).sourceId(), "# parsed"));
        given(aiClient.selectWikiContext(any(WikiContextSelectionRequest.class)))
                .willReturn(new WikiContextSelectionResponse(List.of(), "신규 생성 필요"));
        given(fileStorage.storeParsedMarkdown(eq("ALL"), anyLong(), anyString()))
                .willReturn("wiki/ALL/sources/parsed.md");
        given(wikiTransformationService.requestForAddedDocument(
                anyLong(), anyLong(), anyString(), anyString(), anyList()))
                .willReturn(new com.ajt.backend.global.ai.client.WikiTransformationResponse(
                        "변환 완료", List.of(), List.of(), List.of(), List.of()));
        given(transactionService.applyAddedDocument(eq(15L), eq("ALL"), any()))
                .willThrow(new IllegalStateException("반영 실패"));
        given(transactionService.applyAddedDocument(eq(16L), eq("ALL"), any()))
                .willReturn(new WikiTransformationResult(List.of(301L), "반영 완료"));

        worker.parse(job);

        assertThat(first.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(first.failureReason()).isEqualTo("반영 실패");
        assertThat(second.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(second.documentWikiRefs()).containsExactly(301L);
        org.mockito.Mockito.verify(transactionService)
                .applyAddedDocument(eq(16L), eq("ALL"), any());
    }

    @Test
    @DisplayName("문서 순차 처리 메서드는 AI 호출을 트랜잭션으로 감싸지 않는다")
    void parseDoesNotOpenTransactionAroundAiCalls() throws Exception {
        Method parse = DocumentParseWorker.class.getMethod("parse", AiJob.class);

        assertThat(parse.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("문서를 업로드 순서대로 파싱하고 Wiki 변환까지 마치면 완료 처리한다")
    void parsesDocumentsInUploadOrder() throws Exception {
        Document first = document(15L, "first.md");
        Document second = document(16L, "second.md");
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L, 16L));
        assignId(job, 42L);
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(second, first));
        given(fileStorage.load(first.originalPath())).willReturn(resource("first"));
        given(fileStorage.load(second.originalPath())).willReturn(resource("second"));
        given(wikiTransformationService.currentIndex("ALL"))
                .willReturn("# 목차\n- [휴가 규정](pages/101.md) — 연차와 반차 사용 기준");
        given(aiClient.parseSource(any(SourceParseRequest.class))).willAnswer(invocation -> {
            SourceParseRequest request = invocation.getArgument(0);
            capturedSourceIds.add(request.sourceId());
            return response(request.sourceId(), "# " + ("15".equals(request.sourceId()) ? "first" : "second"));
        });
        given(aiClient.selectWikiContext(any(WikiContextSelectionRequest.class))).willAnswer(invocation -> {
            WikiContextSelectionRequest request = invocation.getArgument(0);
            capturedContextSelections.add(request);
            return new WikiContextSelectionResponse(List.of("101", "108"), "관련 Wiki");
        });
        given(fileStorage.storeParsedMarkdown("ALL", 15L, "# first"))
                .willReturn("wiki/ALL/sources/15/parsed.md");
        given(fileStorage.storeParsedMarkdown("ALL", 16L, "# second"))
                .willReturn("wiki/ALL/sources/16/parsed.md");
        given(wikiTransformationService.requestForAddedDocument(
                anyLong(),
                anyLong(),
                anyString(),
                anyString(),
                anyList()
        )).willReturn(transformationResponse("휴가 규정을 Wiki에 반영했습니다."));
        given(transactionService.applyAddedDocument(anyLong(), anyString(), any()))
                .willReturn(new WikiTransformationResult(List.of(101L, 205L), "휴가 규정을 Wiki에 반영했습니다."));

        worker.parse(job);

        assertThat(capturedSourceIds).containsExactly("15", "16");
        assertThat(first.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(first.parsedPath()).isEqualTo("wiki/ALL/sources/15/parsed.md");
        assertThat(first.documentWikiRefs()).containsExactly(101L, 205L);
        assertThat(second.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(second.parsedPath()).isEqualTo("wiki/ALL/sources/16/parsed.md");
        assertThat(job.status()).isEqualTo(AiJobStatus.COMPLETED);
        assertThat(capturedContextSelections)
                .extracting(WikiContextSelectionRequest::documentId)
                .containsExactly("15", "16");
        assertThat(capturedContextSelections.get(0).jobId()).isEqualTo(String.valueOf(job.id()));
        assertThat(capturedContextSelections.get(0).scopeKey()).isEqualTo("ALL");
        assertThat(capturedContextSelections.get(0).parsedMarkdown()).isEqualTo("# first");
        assertThat(capturedContextSelections.get(0).currentIndex())
                .isEqualTo("# 목차\n- [휴가 규정](pages/101.md) — 연차와 반차 사용 기준");
    }

    @Test
    @DisplayName("문맥 선택 결과를 Wiki 변환 호출에 그대로 넘긴다")
    void passesSelectedWikiIdsToTransformation() throws Exception {
        Document document = document(15L, "first.md");
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L));
        assignId(job, 42L);
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of(document));
        given(fileStorage.load(document.originalPath())).willReturn(resource("first"));
        given(wikiTransformationService.currentIndex("ALL")).willReturn("# 목차");
        given(aiClient.parseSource(any(SourceParseRequest.class)))
                .willReturn(response("15", "# 취업 규칙"));
        given(aiClient.selectWikiContext(any(WikiContextSelectionRequest.class)))
                .willReturn(new WikiContextSelectionResponse(List.of("101", "108"), "관련 Wiki"));
        given(fileStorage.storeParsedMarkdown("ALL", 15L, "# 취업 규칙"))
                .willReturn("wiki/ALL/sources/15/parsed.md");
        given(wikiTransformationService.requestForAddedDocument(
                eq(42L),
                eq(15L),
                eq("ALL"),
                eq("# 취업 규칙"),
                eq(List.of(101L, 108L))
        )).willReturn(transformationResponse("반영 완료"));
        given(transactionService.applyAddedDocument(eq(15L), eq("ALL"), any()))
                .willReturn(new WikiTransformationResult(List.of(101L), "반영 완료"));

        worker.parse(job);

        assertThat(document.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(document.documentWikiRefs()).containsExactly(101L);
    }

    @Test
    @DisplayName("중간 문서가 실패해도 다음 문서 파싱을 계속한다")
    void continuesAfterDocumentFailure() throws Exception {
        Document first = document(15L, "first.md");
        Document second = document(16L, "second.md");
        Document third = document(17L, "third.md");
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L, 16L, 17L));
        assignId(job, 42L);
        given(documentRepository.findAllById(List.of(15L, 16L, 17L))).willReturn(List.of(first, second, third));
        given(fileStorage.load(first.originalPath())).willReturn(resource("first"));
        given(fileStorage.load(second.originalPath())).willReturn(resource("second"));
        given(fileStorage.load(third.originalPath())).willReturn(resource("third"));
        given(wikiTransformationService.currentIndex("ALL")).willReturn("# 목차");
        given(aiClient.parseSource(any(SourceParseRequest.class))).willAnswer(invocation -> {
            SourceParseRequest request = invocation.getArgument(0);
            capturedSourceIds.add(request.sourceId());
            if ("16".equals(request.sourceId())) {
                throw timeout();
            }
            return response(request.sourceId(), "# parsed " + request.sourceId());
        });
        given(aiClient.selectWikiContext(any(WikiContextSelectionRequest.class)))
                .willReturn(new WikiContextSelectionResponse(List.of(), "신규 생성 필요"));
        given(fileStorage.storeParsedMarkdown("ALL", 15L, "# parsed 15"))
                .willReturn("wiki/ALL/sources/15/parsed.md");
        given(fileStorage.storeParsedMarkdown("ALL", 17L, "# parsed 17"))
                .willReturn("wiki/ALL/sources/17/parsed.md");
        given(wikiTransformationService.requestForAddedDocument(
                anyLong(),
                anyLong(),
                anyString(),
                anyString(),
                anyList()
        )).willReturn(transformationResponse("반영 완료"));
        given(transactionService.applyAddedDocument(anyLong(), anyString(), any()))
                .willReturn(new WikiTransformationResult(List.of(300L), "반영 완료"));

        worker.parse(job);

        assertThat(capturedSourceIds).containsExactly("15", "16", "17");
        assertThat(first.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(second.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(second.failureReason()).isEqualTo("FastAPI 응답 시간이 초과되었습니다.");
        assertThat(third.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(job.status()).isEqualTo(AiJobStatus.COMPLETED);
    }

    @Test
    @DisplayName("Wiki 변환이 실패하면 해당 문서만 실패로 남기고 다음 문서를 계속 처리한다")
    void continuesAfterTransformationFailure() throws Exception {
        Document first = document(15L, "first.md");
        Document second = document(16L, "second.md");
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L, 16L));
        assignId(job, 42L);
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(first, second));
        given(fileStorage.load(first.originalPath())).willReturn(resource("first"));
        given(fileStorage.load(second.originalPath())).willReturn(resource("second"));
        given(wikiTransformationService.currentIndex("ALL")).willReturn("# 목차");
        given(aiClient.parseSource(any(SourceParseRequest.class))).willAnswer(invocation ->
                response(invocation.<SourceParseRequest>getArgument(0).sourceId(), "# parsed"));
        given(aiClient.selectWikiContext(any(WikiContextSelectionRequest.class)))
                .willReturn(new WikiContextSelectionResponse(List.of(), "신규 생성 필요"));
        given(fileStorage.storeParsedMarkdown(eq("ALL"), anyLong(), anyString()))
                .willReturn("wiki/ALL/sources/parsed.md");
        given(wikiTransformationService.requestForAddedDocument(
                anyLong(),
                eq(15L),
                anyString(),
                anyString(),
                anyList()
        )).willThrow(timeout());
        given(wikiTransformationService.requestForAddedDocument(
                anyLong(),
                eq(16L),
                anyString(),
                anyString(),
                anyList()
        )).willReturn(transformationResponse("반영 완료"));
        given(transactionService.applyAddedDocument(eq(16L), eq("ALL"), any()))
                .willReturn(new WikiTransformationResult(List.of(301L), "반영 완료"));

        worker.parse(job);

        assertThat(first.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(first.failureReason()).isEqualTo("FastAPI 응답 시간이 초과되었습니다.");
        assertThat(second.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(second.documentWikiRefs()).containsExactly(301L);
    }

    @Test
    @DisplayName("문서가 하나라도 성공하면 작업을 완료로 끝내고 문서별 결과를 기록한다")
    void finishesJobWithDocumentResults() throws Exception {
        Document first = document(15L, "first.md");
        Document second = document(16L, "second.md");
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L, 16L));
        assignId(job, 42L);
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(first, second));
        given(fileStorage.load(first.originalPath())).willReturn(resource("first"));
        given(fileStorage.load(second.originalPath())).willReturn(resource("second"));
        given(wikiTransformationService.currentIndex("ALL")).willReturn("# 목차");
        given(aiClient.parseSource(any(SourceParseRequest.class))).willAnswer(invocation ->
                response(invocation.<SourceParseRequest>getArgument(0).sourceId(), "# parsed"));
        given(aiClient.selectWikiContext(any(WikiContextSelectionRequest.class)))
                .willReturn(new WikiContextSelectionResponse(List.of(), "신규 생성 필요"));
        given(fileStorage.storeParsedMarkdown(eq("ALL"), anyLong(), anyString()))
                .willReturn("wiki/ALL/sources/parsed.md");
        given(wikiTransformationService.requestForAddedDocument(
                anyLong(),
                eq(15L),
                anyString(),
                anyString(),
                anyList()
        )).willReturn(transformationResponse("휴가 규정을 Wiki에 반영했습니다."));
        given(transactionService.applyAddedDocument(eq(15L), eq("ALL"), any()))
                .willReturn(new WikiTransformationResult(List.of(101L), "휴가 규정을 Wiki에 반영했습니다."));
        given(wikiTransformationService.requestForAddedDocument(
                anyLong(),
                eq(16L),
                anyString(),
                anyString(),
                anyList()
        )).willThrow(failureWithStage());

        worker.parse(job);

        assertThat(job.status()).isEqualTo(AiJobStatus.COMPLETED);
        assertThat(job.finishedAt()).isNotNull();
        assertThat(job.failureReason()).isNull();
        assertThat(job.documentResults())
                .extracting(
                        AiJob.DocumentParseResult::documentId,
                        AiJob.DocumentParseResult::success,
                        AiJob.DocumentParseResult::summary,
                        AiJob.DocumentParseResult::failureStage
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(15L, true, "휴가 규정을 Wiki에 반영했습니다.", null),
                        org.assertj.core.groups.Tuple.tuple(16L, false, null, "agent_timeout")
                );
    }

    @Test
    @DisplayName("모든 문서가 실패하면 작업을 실패로 끝낸다")
    void failsJobWhenEveryDocumentFails() throws Exception {
        Document only = document(15L, "first.md");
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L));
        assignId(job, 42L);
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of(only));
        given(fileStorage.load(only.originalPath())).willReturn(resource("first"));
        given(wikiTransformationService.currentIndex("ALL")).willReturn("# 목차");
        given(aiClient.parseSource(any(SourceParseRequest.class))).willThrow(timeout());

        worker.parse(job);

        assertThat(only.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(job.status()).isEqualTo(AiJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("FastAPI 응답 시간이 초과되었습니다.");
        assertThat(job.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("삭제된 문서는 실패로 기록하고 남은 문서를 계속 처리한다")
    void recordsMissingDocumentAsFailure() throws Exception {
        Document second = document(16L, "second.md");
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L, 16L));
        assignId(job, 42L);
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(second));
        given(fileStorage.load(second.originalPath())).willReturn(resource("second"));
        given(wikiTransformationService.currentIndex("ALL")).willReturn("# 목차");
        given(aiClient.parseSource(any(SourceParseRequest.class))).willReturn(response("16", "# parsed"));
        given(aiClient.selectWikiContext(any(WikiContextSelectionRequest.class)))
                .willReturn(new WikiContextSelectionResponse(List.of(), "신규 생성 필요"));
        given(fileStorage.storeParsedMarkdown(eq("ALL"), anyLong(), anyString()))
                .willReturn("wiki/ALL/sources/parsed.md");
        given(wikiTransformationService.requestForAddedDocument(
                anyLong(),
                eq(16L),
                anyString(),
                anyString(),
                anyList()
        )).willReturn(transformationResponse("반영 완료"));
        given(transactionService.applyAddedDocument(eq(16L), eq("ALL"), any()))
                .willReturn(new WikiTransformationResult(List.of(101L), "반영 완료"));

        worker.parse(job);

        assertThat(job.status()).isEqualTo(AiJobStatus.COMPLETED);
        assertThat(job.documentResults())
                .extracting(
                        AiJob.DocumentParseResult::documentId,
                        AiJob.DocumentParseResult::success,
                        AiJob.DocumentParseResult::failureReason
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(15L, false, "문서를 찾을 수 없습니다."),
                        org.assertj.core.groups.Tuple.tuple(16L, true, null)
                );
    }

    @Test
    @DisplayName("문서 저장소 조회 자체가 실패하면 작업 자체를 실패로 끝낸다")
    void failsJobWhenRepositoryFails() throws Exception {
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L));
        assignId(job, 42L);
        given(documentRepository.findAllById(List.of(15L)))
                .willThrow(new IllegalStateException("DB 연결 실패"));

        worker.parse(job);

        assertThat(job.status()).isEqualTo(AiJobStatus.FAILED);
        assertThat(job.failureReason()).startsWith("작업 대상 문서를 읽지 못했습니다");
        assertThat(job.documentResults()).isEmpty();
    }

    private AiClientException failureWithStage() {
        return new AiClientException(
                AiClientFailureType.SERVER_ERROR,
                500,
                "WIKI_TRANSFORMATION_FAILED",
                "Wiki 변환에 실패했습니다.",
                List.<FieldErrorResponse>of(),
                null,
                "agent_timeout"
        );
    }

    private final List<String> capturedSourceIds = new ArrayList<>();
    private final List<WikiContextSelectionRequest> capturedContextSelections = new ArrayList<>();

    private AiClientException timeout() {
        return new AiClientException(
                AiClientFailureType.TIMEOUT,
                null,
                null,
                "FastAPI 응답 시간이 초과되었습니다.",
                List.<FieldErrorResponse>of(),
                null
        );
    }

    @Test
    @DisplayName("걷어내기 계획이면 파싱하지 않고 작업의 범위에서 문서를 제거한다")
    void removesDocumentFromJobScopeWithoutParsing() throws Exception {
        // 범위 변경 재처리 시점에는 문서가 이미 새 범위(D1-D3)로 옮겨져 있다.
        Document document = document(15L, "rule.md");
        setScopeKey(document, "D1-D3");
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/1", List.of(15L));
        assignId(job, 42L);
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of(document));
        given(aiJobRepository.findById(42L)).willReturn(Optional.of(job));
        given(wikiTransformationService.currentIndex("ALL")).willReturn("# 목차");
        given(aiClient.selectWikiContext(any(WikiContextSelectionRequest.class)))
                .willReturn(new WikiContextSelectionResponse(List.of("101"), "이 문서를 근거로 쓴 위키"));
        given(wikiTransformationService.requestForDocumentChange(
                anyLong(), anyLong(), anyString(), any(), any(), anyString(), anyList()))
                .willReturn(transformationResponse("걷어내기 완료"));
        given(transactionService.applyRemovedDocument(anyLong(), anyString(), any()))
                .willReturn(new WikiTransformationResult(List.of(101L), "걷어내기 완료"));

        worker.parse(job, DocumentReprocessPlan.removed(15L, "# 옛 취업규칙\n본문"));

        // 파싱·파일 접근이 전혀 없다 — 원본은 이미 새 범위로 옮겨졌다.
        org.mockito.Mockito.verify(aiClient, org.mockito.Mockito.never())
                .parseSource(any(SourceParseRequest.class));
        org.mockito.Mockito.verify(fileStorage, org.mockito.Mockito.never())
                .storeParsedMarkdown(anyString(), anyLong(), anyString());

        // 문맥 선택·변환 모두 문서의 현재 범위가 아니라 작업의 범위를 대상으로 한다.
        org.mockito.Mockito.verify(aiClient).selectWikiContext(org.mockito.ArgumentMatchers.argThat(
                request -> request.scopeKey().equals("ALL")
                        && request.changeType() == WikiDocumentChangeType.DOCUMENT_REMOVED
                        && request.parsedMarkdown() == null
                        && request.removedParsedMarkdown().equals("# 옛 취업규칙\n본문")));
        org.mockito.Mockito.verify(wikiTransformationService).requestForDocumentChange(
                eq(42L), eq(15L), eq("ALL"), eq(WikiDocumentChangeType.DOCUMENT_REMOVED),
                eq(null), eq("# 옛 취업규칙\n본문"), eq(List.of(101L)));
        org.mockito.Mockito.verify(transactionService).applyRemovedDocument(15L, "ALL", transformationResponse("걷어내기 완료"));

        // 문서 상태는 새 범위 작업이 관리한다 — 걷어내기가 건드리지 않는다.
        assertThat(document.status()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(job.status()).isEqualTo(AiJobStatus.COMPLETED);
    }

    private void setScopeKey(Document document, String scopeKey) throws ReflectiveOperationException {
        Field field = Document.class.getDeclaredField("scopeKey");
        field.setAccessible(true);
        field.set(document, scopeKey);
    }

    private Document document(long id, String name) throws ReflectiveOperationException {
        Document document = Document.uploaded(
                10L,
                7L,
                "ALL",
                name,
                "wiki/ALL/sources/" + id + "/original.md",
                "text/markdown",
                100L
        );
        assignId(document, id);
        return document;
    }

    private ByteArrayResource resource(String content) {
        return new ByteArrayResource(content.getBytes());
    }

    private SourceParseResponse response(String sourceId, String parsedMarkdown) {
        return new SourceParseResponse("req-" + sourceId, SourceType.WIKI, sourceId, parsedMarkdown, List.of());
    }

    private com.ajt.backend.global.ai.client.WikiTransformationResponse transformationResponse(String summary) {
        return new com.ajt.backend.global.ai.client.WikiTransformationResponse(
                summary, List.of(), List.of(), List.of(), List.of());
    }

    private void assignId(Object target, long id) throws ReflectiveOperationException {
        Field idField = target.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(target, id);
    }
}
