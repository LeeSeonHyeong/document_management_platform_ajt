package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.wiki.service.WikiTransformationApplier;
import com.ajt.backend.global.ai.client.WikiTransformationResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentWikiTransformationTransactionService {

    private final DocumentRepository documentRepository;
    private final WikiTransformationApplier applier;

    public DocumentWikiTransformationTransactionService(
            DocumentRepository documentRepository,
            WikiTransformationApplier applier
    ) {
        this.documentRepository = documentRepository;
        this.applier = applier;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WikiTransformationResult applyAddedDocument(
            long documentId,
            String scopeKey,
            WikiTransformationResponse response
    ) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("문서를 찾을 수 없습니다: " + documentId));
        List<Long> affectedWikiIds = applier.apply(scopeKey, documentId, response);
        document.completeProcessing(affectedWikiIds);
        return new WikiTransformationResult(affectedWikiIds, response.summary());
    }

    public record WikiTransformationResult(List<Long> affectedWikiIds, String summary) {

        public WikiTransformationResult {
            affectedWikiIds = List.copyOf(affectedWikiIds);
        }
    }
}
