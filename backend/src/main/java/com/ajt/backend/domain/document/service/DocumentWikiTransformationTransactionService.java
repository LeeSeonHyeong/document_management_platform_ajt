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

    /**
     * 문서가 이 범위에서 빠진 변환 결과를 반영합니다. (FR-DOC-008 범위 변경)
     *
     * <p>문서 엔티티의 처리 상태는 건드리지 않는다 — 문서는 이미 새 범위로 옮겨져 그쪽 작업이
     * 상태를 관리하고, 옛 범위 정리는 문서의 처리 상태와 무관하기 때문이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WikiTransformationResult applyRemovedDocument(
            long documentId,
            String scopeKey,
            WikiTransformationResponse response
    ) {
        WikiTransformationApplier.RemovedDocumentResult removed =
                applier.applyRemovedDocument(scopeKey, documentId, response);
        return new WikiTransformationResult(
                removed.affectedWikiIds(), response.summary(), removed.referencingWikiCount());
    }

    /**
     * @param referencingWikiCount 걷어내기 <b>전에</b> 이 문서를 근거로 삼던 Wiki 수입니다.
     *                             걷어내기가 아닌 반영에서는 0입니다(S15P11B106-225).
     */
    public record WikiTransformationResult(
            List<Long> affectedWikiIds,
            String summary,
            int referencingWikiCount
    ) {

        public WikiTransformationResult {
            affectedWikiIds = List.copyOf(affectedWikiIds);
        }

        public WikiTransformationResult(List<Long> affectedWikiIds, String summary) {
            this(affectedWikiIds, summary, 0);
        }
    }
}
