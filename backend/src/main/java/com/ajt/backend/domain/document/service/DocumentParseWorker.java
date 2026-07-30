package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.wiki.service.WikiTransformationService;
import com.ajt.backend.domain.document.service.DocumentWikiTransformationTransactionService.WikiTransformationResult;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.SourceParseRequest;
import com.ajt.backend.global.ai.client.SourceParseResponse;
import com.ajt.backend.global.ai.client.SourceType;
import com.ajt.backend.global.ai.client.WikiContextSelectionRequest;
import com.ajt.backend.global.ai.client.WikiContextSelectionResponse;
import com.ajt.backend.global.ai.client.WikiDocumentChangeType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class DocumentParseWorker {

    private final DocumentRepository documentRepository;
    private final AiJobRepository aiJobRepository;
    private final DocumentFileStorage fileStorage;
    private final AiClient aiClient;
    private final WikiTransformationService wikiTransformationService;
    private final DocumentWikiTransformationTransactionService transactionService;

    public DocumentParseWorker(
            DocumentRepository documentRepository,
            AiJobRepository aiJobRepository,
            DocumentFileStorage fileStorage,
            AiClient aiClient,
            WikiTransformationService wikiTransformationService,
            DocumentWikiTransformationTransactionService transactionService
    ) {
        this.documentRepository = documentRepository;
        this.aiJobRepository = aiJobRepository;
        this.fileStorage = fileStorage;
        this.aiClient = aiClient;
        this.wikiTransformationService = wikiTransformationService;
        this.transactionService = transactionService;
    }

    public void parse(AiJob job) {
        job.start();
        aiJobRepository.save(job);
        Map<Long, Document> documentsById;
        try {
            documentsById = documentsById(job.documentIds());
        } catch (RuntimeException exception) {
            job.fail("작업 대상 문서를 읽지 못했습니다: " + exception.getMessage());
            aiJobRepository.save(job);
            return;
        }
        List<AiJob.DocumentParseResult> documentResults = new ArrayList<>();
        for (Long documentId : job.documentIds()) {
            Document document = documentsById.get(documentId);
            if (document == null) {
                // 업로드 후 삭제된 문서다. 나머지 문서는 계속 처리하고 이 문서만 실패로 남긴다.
                documentResults.add(AiJob.DocumentParseResult.failed(
                        documentId,
                        "문서를 찾을 수 없습니다.",
                        null
                ));
                continue;
            }
            documentResults.add(parseDocument(job, document));
        }
        job.finish(documentResults);
        aiJobRepository.save(job);
    }

    /**
     * 업로드 순서대로 처리하기 위해 문서를 ID로 찾을 수 있게 모읍니다.
     */
    private Map<Long, Document> documentsById(List<Long> documentIds) {
        return documentRepository.findAllById(documentIds)
                .stream()
                .collect(Collectors.toMap(Document::id, Function.identity()));
    }

    private AiJob.DocumentParseResult parseDocument(AiJob job, Document document) {
        document.startParsing();
        documentRepository.save(document);
        String parsedMarkdown;
        List<Long> selectedWikiIds;
        try {
            SourceParseResponse response = aiClient.parseSource(new SourceParseRequest(
                    UUID.randomUUID().toString(),
                    SourceType.WIKI,
                    String.valueOf(document.id()),
                    fileStorage.load(document.originalPath()),
                    document.originalFileName(),
                    document.mimeType()
            ));
            parsedMarkdown = response.parsedMarkdown();
            String parsedPath = storeParsedMarkdown(document, parsedMarkdown);
            WikiContextSelectionResponse selection = aiClient.selectWikiContext(new WikiContextSelectionRequest(
                    String.valueOf(job.id()),
                    String.valueOf(document.id()),
                    document.scopeKey(),
                    WikiDocumentChangeType.DOCUMENT_ADDED,
                    parsedMarkdown,
                    null,
                    wikiTransformationService.currentIndex(document.scopeKey())
            ));
            selectedWikiIds = wikiIds(selection);
            document.completeParsing(parsedPath, selectedWikiIds);
            documentRepository.save(document);
        } catch (AiClientException exception) {
            document.failParsing(failureReason(exception));
            documentRepository.save(document);
            return AiJob.DocumentParseResult.failed(
                    document.id(),
                    failureReason(exception),
                    exception.failureStage()
            );
        } catch (RuntimeException exception) {
            document.failParsing(exception.getMessage());
            documentRepository.save(document);
            return AiJob.DocumentParseResult.failed(document.id(), exception.getMessage(), null);
        }
        return transformWiki(job, document, parsedMarkdown, selectedWikiIds);
    }

    /**
     * 파싱된 문서를 Wiki로 변환하고 결과를 반영합니다.
     * 변환이 실패해도 이 문서만 실패로 남기고 작업의 다음 문서는 계속 처리합니다.
     */
    private AiJob.DocumentParseResult transformWiki(
            AiJob job,
            Document document,
            String parsedMarkdown,
            List<Long> selectedWikiIds
    ) {
        try {
            var response = wikiTransformationService.requestForAddedDocument(
                    job.id(),
                    document.id(),
                    document.scopeKey(),
                    parsedMarkdown,
                    selectedWikiIds
            );
            WikiTransformationResult result = transactionService.applyAddedDocument(
                    document.id(), document.scopeKey(), response);
            // 반영 트랜잭션은 별도로 조회한 엔티티를 완료 처리한다. 이 인스턴스도 작업 결과를
            // 조립할 때 일관된 상태를 보도록만 맞추며, 여기서 다시 저장하지는 않는다.
            document.completeProcessing(result.affectedWikiIds());
            return AiJob.DocumentParseResult.succeeded(document.id(), result.summary());
        } catch (AiClientException exception) {
            document.failProcessing(failureReason(exception));
            documentRepository.save(document);
            return AiJob.DocumentParseResult.failed(
                    document.id(),
                    failureReason(exception),
                    exception.failureStage()
            );
        } catch (RuntimeException exception) {
            document.failProcessing(exception.getMessage());
            documentRepository.save(document);
            return AiJob.DocumentParseResult.failed(document.id(), exception.getMessage(), null);
        }
    }

    private List<Long> wikiIds(WikiContextSelectionResponse selection) {
        return selection.wikiIds()
                .stream()
                .map(Long::parseLong)
                .toList();
    }

    private String storeParsedMarkdown(Document document, String parsedMarkdown) {
        try {
            return fileStorage.storeParsedMarkdown(document.scopeKey(), document.id(), parsedMarkdown);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String failureReason(AiClientException exception) {
        if (exception.upstreamMessage() != null && !exception.upstreamMessage().isBlank()) {
            return exception.upstreamMessage();
        }
        return exception.failureType().name();
    }
}
