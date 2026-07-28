package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.AiClientFailureType;
import com.ajt.backend.global.ai.client.SourceParseRequest;
import com.ajt.backend.global.ai.client.SourceParseResponse;
import com.ajt.backend.global.ai.client.SourceType;
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
    private final DocumentParseWorker worker = new DocumentParseWorker(documentRepository, aiJobRepository, fileStorage, aiClient);

    @Test
    @DisplayName("문서를 업로드 순서대로 파싱하고 Markdown 파일 경로를 저장한다")
    void parsesDocumentsInUploadOrder() throws Exception {
        Document first = document(15L, "first.md");
        Document second = document(16L, "second.md");
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L, 16L));
        given(documentRepository.findAllById(List.of(15L, 16L))).willReturn(List.of(second, first));
        given(fileStorage.load(first.originalPath())).willReturn(resource("first"));
        given(fileStorage.load(second.originalPath())).willReturn(resource("second"));
        given(aiClient.parseSource(any(SourceParseRequest.class))).willAnswer(invocation -> {
            SourceParseRequest request = invocation.getArgument(0);
            capturedSourceIds.add(request.sourceId());
            return response(request.sourceId(), "# " + ("15".equals(request.sourceId()) ? "first" : "second"));
        });
        given(fileStorage.storeParsedMarkdown("ALL", 15L, "# first"))
                .willReturn("wiki/ALL/sources/15/parsed.md");
        given(fileStorage.storeParsedMarkdown("ALL", 16L, "# second"))
                .willReturn("wiki/ALL/sources/16/parsed.md");

        worker.parse(job);

        assertThat(capturedSourceIds).containsExactly("15", "16");
        assertThat(first.status()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(first.parsedPath()).isEqualTo("wiki/ALL/sources/15/parsed.md");
        assertThat(second.status()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(second.parsedPath()).isEqualTo("wiki/ALL/sources/16/parsed.md");
        assertThat(job.status()).isEqualTo(AiJobStatus.PROCESSING);
    }

    @Test
    @DisplayName("중간 문서가 실패해도 다음 문서 파싱을 계속한다")
    void continuesAfterDocumentFailure() throws Exception {
        Document first = document(15L, "first.md");
        Document second = document(16L, "second.md");
        Document third = document(17L, "third.md");
        AiJob job = AiJob.waiting(10L, "ALL", "wiki/ALL/jobs/1", List.of(15L, 16L, 17L));
        given(documentRepository.findAllById(List.of(15L, 16L, 17L))).willReturn(List.of(first, second, third));
        given(fileStorage.load(first.originalPath())).willReturn(resource("first"));
        given(fileStorage.load(second.originalPath())).willReturn(resource("second"));
        given(fileStorage.load(third.originalPath())).willReturn(resource("third"));
        given(aiClient.parseSource(any(SourceParseRequest.class))).willAnswer(invocation -> {
            SourceParseRequest request = invocation.getArgument(0);
            capturedSourceIds.add(request.sourceId());
            if ("16".equals(request.sourceId())) {
                throw new AiClientException(
                        AiClientFailureType.TIMEOUT,
                        null,
                        null,
                        "FastAPI 응답 시간이 초과되었습니다.",
                        List.<FieldErrorResponse>of(),
                        null
                );
            }
            return response(request.sourceId(), "# parsed " + request.sourceId());
        });
        given(fileStorage.storeParsedMarkdown("ALL", 15L, "# parsed 15"))
                .willReturn("wiki/ALL/sources/15/parsed.md");
        given(fileStorage.storeParsedMarkdown("ALL", 17L, "# parsed 17"))
                .willReturn("wiki/ALL/sources/17/parsed.md");

        worker.parse(job);

        assertThat(capturedSourceIds).containsExactly("15", "16", "17");
        assertThat(first.status()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(second.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(second.failureReason()).isEqualTo("FastAPI 응답 시간이 초과되었습니다.");
        assertThat(third.status()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(job.status()).isEqualTo(AiJobStatus.PROCESSING);
    }

    private final List<String> capturedSourceIds = new ArrayList<>();

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
