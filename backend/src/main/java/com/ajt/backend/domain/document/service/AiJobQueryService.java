package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.api.AiJobResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiJobQueryService {

    private final CurrentMemberProvider currentMemberProvider;
    private final AiJobRepository aiJobRepository;
    private final DocumentRepository documentRepository;

    public AiJobQueryService(
            CurrentMemberProvider currentMemberProvider,
            AiJobRepository aiJobRepository,
            DocumentRepository documentRepository
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.aiJobRepository = aiJobRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional(readOnly = true)
    public AiJobResponse getAiJob(long jobId) {
        requireAdmin();
        AiJob job = aiJobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_JOB_NOT_FOUND));
        Map<Long, Document> documentsById = documentsById(job.documentIds());

        return new AiJobResponse(
                String.valueOf(job.id()),
                job.status().name().toLowerCase(),
                documentResults(job.documentIds(), documentsById, recordedResultsById(job)),
                job.createdAt(),
                job.startedAt(),
                job.finishedAt(),
                job.failureReason()
        );
    }

    private void requireAdmin() {
        CurrentMember currentMember = currentMemberProvider.currentMember();
        if (!currentMember.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private Map<Long, Document> documentsById(List<Long> documentIds) {
        Map<Long, Document> documents = new HashMap<>();
        documentRepository.findAllById(documentIds)
                .forEach(document -> documents.put(document.id(), document));
        return documents;
    }

    /**
     * 작업에 기록된 문서별 결과입니다. 아직 진행 중인 작업은 비어 있습니다.
     */
    private Map<Long, AiJob.DocumentParseResult> recordedResultsById(AiJob job) {
        Map<Long, AiJob.DocumentParseResult> results = new HashMap<>();
        job.documentResults().forEach(result -> results.put(result.documentId(), result));
        return results;
    }

    private List<AiJobResponse.DocumentResultResponse> documentResults(
            List<Long> documentIds,
            Map<Long, Document> documentsById,
            Map<Long, AiJob.DocumentParseResult> recordedResultsById
    ) {
        return documentIds.stream()
                .map(documentId -> toDocumentResult(
                        documentId,
                        documentsById.get(documentId),
                        recordedResultsById.get(documentId),
                        documentIds
                ))
                .sorted(Comparator.comparingInt(AiJobResponse.DocumentResultResponse::order))
                .toList();
    }

    private AiJobResponse.DocumentResultResponse toDocumentResult(
            long documentId,
            Document document,
            AiJob.DocumentParseResult recordedResult,
            List<Long> orderedDocumentIds
    ) {
        DocumentStatus status = document == null ? DocumentStatus.FAILED : document.status();
        return new AiJobResponse.DocumentResultResponse(
                String.valueOf(documentId),
                orderedDocumentIds.indexOf(documentId) + 1,
                responseStatus(status),
                currentStage(status),
                recordedResult == null ? null : recordedResult.summary(),
                failureReasonOf(document, recordedResult)
        );
    }

    private String failureReasonOf(Document document, AiJob.DocumentParseResult recordedResult) {
        if (document == null) {
            return "문서를 찾을 수 없습니다.";
        }
        if (document.failureReason() != null) {
            return document.failureReason();
        }
        return recordedResult == null ? null : recordedResult.failureReason();
    }

    private String responseStatus(DocumentStatus status) {
        return switch (status) {
            case UPLOADED -> "waiting";
            case PARSING -> "processing";
            case PROCESSING -> "processing";
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
        };
    }

    private String currentStage(DocumentStatus status) {
        return switch (status) {
            case UPLOADED -> "waiting";
            case PARSING, FAILED -> "parsing";
            case PROCESSING -> "wiki_pending";
            case COMPLETED -> "wiki_applied";
            case CANCELLED -> "cancelled";
        };
    }
}
