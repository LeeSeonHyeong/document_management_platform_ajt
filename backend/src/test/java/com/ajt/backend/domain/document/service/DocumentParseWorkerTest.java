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
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.AiClientFailureType;
import com.ajt.backend.global.ai.client.SourceParseRequest;
import com.ajt.backend.global.ai.client.SourceParseResponse;
import com.ajt.backend.global.ai.client.SourceType;
import com.ajt.backend.global.ai.client.WikiContextSelectionRequest;
import com.ajt.backend.global.ai.client.WikiContextSelectionResponse;
import com.ajt.backend.global.error.FieldErrorResponse;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

@DisplayName("원본문서 순차 파싱 워커")
class DocumentParseWorkerTest {

    private final DocumentRepository documentRepository = org.mockito.Mockito.mock(DocumentRepository.class);
    private final AiJobRepository aiJobRepository = org.mockito.Mockito.mock(AiJobRepository.class);
    private final DocumentFileStorage fileStorage = org.mockito.Mockito.mock(DocumentFileStorage.class);
    private final AiClient aiClient = org.mockito.Mockito.mock(AiClient.class);
    private final WikiTransformationService wikiTransformationService =
            org.mockito.Mockito.mock(WikiTransformationService.class);
    private final DocumentParseWorker worker = new DocumentParseWorker(
            documentRepository,
            aiJobRepository,
            fileStorage,
            aiClient,
            wikiTransformationService
    );

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
        given(wikiTransformationService.transformForAddedDocument(
                anyLong(),
                anyLong(),
                anyString(),
                anyString(),
                anyList()
        )).willReturn(List.of(101L, 205L));

        worker.parse(job);

        assertThat(capturedSourceIds).containsExactly("15", "16");
        assertThat(first.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(first.parsedPath()).isEqualTo("wiki/ALL/sources/15/parsed.md");
        assertThat(first.documentWikiRefs()).containsExactly(101L, 205L);
        assertThat(second.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(second.parsedPath()).isEqualTo("wiki/ALL/sources/16/parsed.md");
        assertThat(job.status()).isEqualTo(AiJobStatus.PROCESSING);
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
        given(wikiTransformationService.transformForAddedDocument(
                eq(42L),
                eq(15L),
                eq("ALL"),
                eq("# 취업 규칙"),
                eq(List.of(101L, 108L))
        )).willReturn(List.of(101L));

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
        given(wikiTransformationService.transformForAddedDocument(
                anyLong(),
                anyLong(),
                anyString(),
                anyString(),
                anyList()
        )).willReturn(List.of(300L));

        worker.parse(job);

        assertThat(capturedSourceIds).containsExactly("15", "16", "17");
        assertThat(first.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(second.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(second.failureReason()).isEqualTo("FastAPI 응답 시간이 초과되었습니다.");
        assertThat(third.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(job.status()).isEqualTo(AiJobStatus.PROCESSING);
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
        given(wikiTransformationService.transformForAddedDocument(
                anyLong(),
                eq(15L),
                anyString(),
                anyString(),
                anyList()
        )).willThrow(timeout());
        given(wikiTransformationService.transformForAddedDocument(
                anyLong(),
                eq(16L),
                anyString(),
                anyString(),
                anyList()
        )).willReturn(List.of(301L));

        worker.parse(job);

        assertThat(first.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(first.failureReason()).isEqualTo("FastAPI 응답 시간이 초과되었습니다.");
        assertThat(second.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(second.documentWikiRefs()).containsExactly(301L);
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

    private void assignId(Object target, long id) throws ReflectiveOperationException {
        Field idField = target.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(target, id);
    }
}
