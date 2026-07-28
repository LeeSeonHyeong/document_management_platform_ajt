package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.SourceParseRequest;
import com.ajt.backend.global.ai.client.SourceParseResponse;
import com.ajt.backend.global.ai.client.SourceType;
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

    public DocumentParseWorker(
            DocumentRepository documentRepository,
            AiJobRepository aiJobRepository,
            DocumentFileStorage fileStorage,
            AiClient aiClient
    ) {
        this.documentRepository = documentRepository;
        this.aiJobRepository = aiJobRepository;
        this.fileStorage = fileStorage;
        this.aiClient = aiClient;
    }

    @Transactional
    public void parse(AiJob job) {
        job.start();
        aiJobRepository.save(job);
        for (Document document : orderedDocuments(job.documentIds())) {
            parseDocument(document);
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

    private void parseDocument(Document document) {
        document.startParsing();
        try {
            SourceParseResponse response = aiClient.parseSource(new SourceParseRequest(
                    UUID.randomUUID().toString(),
                    SourceType.WIKI,
                    String.valueOf(document.id()),
                    fileStorage.load(document.originalPath()),
                    document.originalFileName(),
                    document.mimeType()
            ));
            String parsedPath = storeParsedMarkdown(document, response.parsedMarkdown());
            document.completeParsing(parsedPath);
        } catch (AiClientException exception) {
            document.failParsing(failureReason(exception));
        } catch (RuntimeException exception) {
            document.failParsing(exception.getMessage());
        }
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
