package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.wiki.service.WikiTransformationService;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DocumentParseWorker {

    private final DocumentRepository documentRepository;
    private final AiJobRepository aiJobRepository;
    private final DocumentFileStorage fileStorage;
    private final AiClient aiClient;
    private final WikiTransformationService wikiTransformationService;

    public DocumentParseWorker(
            DocumentRepository documentRepository,
            AiJobRepository aiJobRepository,
            DocumentFileStorage fileStorage,
            AiClient aiClient,
            WikiTransformationService wikiTransformationService
    ) {
        this.documentRepository = documentRepository;
        this.aiJobRepository = aiJobRepository;
        this.fileStorage = fileStorage;
        this.aiClient = aiClient;
        this.wikiTransformationService = wikiTransformationService;
    }

    @Transactional
    public void parse(AiJob job) {
        job.start();
        aiJobRepository.save(job);
        for (Document document : orderedDocuments(job.documentIds())) {
            parseDocument(job, document);
        }
    }

    private List<Document> orderedDocuments(List<Long> documentIds) {
        Map<Long, Document> documentsById = documentRepository.findAllById(documentIds)
                .stream()
                .collect(Collectors.toMap(Document::id, Function.identity()));
        return documentIds.stream()
                .map(documentsById::get)
                .sorted(Comparator.comparing(document -> documentIds.indexOf(document.id())))
                .toList();
    }

    private void parseDocument(AiJob job, Document document) {
        document.startParsing();
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
        } catch (AiClientException exception) {
            document.failParsing(failureReason(exception));
            return;
        } catch (RuntimeException exception) {
            document.failParsing(exception.getMessage());
            return;
        }
        transformWiki(job, document, parsedMarkdown, selectedWikiIds);
    }

    /**
     * 파싱된 문서를 Wiki로 변환하고 결과를 반영합니다.
     * 변환이 실패해도 이 문서만 실패로 남기고 작업의 다음 문서는 계속 처리합니다.
     */
    private void transformWiki(AiJob job, Document document, String parsedMarkdown, List<Long> selectedWikiIds) {
        try {
            List<Long> affectedWikiIds = wikiTransformationService.transformForAddedDocument(
                    job.id(),
                    document.id(),
                    document.scopeKey(),
                    parsedMarkdown,
                    selectedWikiIds
            );
            document.completeProcessing(affectedWikiIds);
        } catch (AiClientException exception) {
            document.failProcessing(failureReason(exception));
        } catch (RuntimeException exception) {
            document.failProcessing(exception.getMessage());
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
