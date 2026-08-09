package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.service.WikiTransformationApplier;
import com.ajt.backend.global.ai.client.WikiTransformationResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentWikiTransformationTransactionService {

    private final DocumentRepository documentRepository;
    private final WikiTransformationApplier applier;
    private final WikiRepository wikiRepository;

    @Autowired
    public DocumentWikiTransformationTransactionService(
            DocumentRepository documentRepository,
            WikiTransformationApplier applier,
            WikiRepository wikiRepository
    ) {
        this.documentRepository = documentRepository;
        this.applier = applier;
        this.wikiRepository = wikiRepository;
    }

    DocumentWikiTransformationTransactionService(
            DocumentRepository documentRepository,
            WikiTransformationApplier applier
    ) {
        this(documentRepository, applier, null);
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
        return new WikiTransformationResult(affectedWikiIds, affectedWikisOf(affectedWikiIds), response.summary());
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
                removed.affectedWikiIds(),
                affectedWikisOf(removed.affectedWikiIds()),
                response.summary(),
                removed.referencingWikiCount());
    }

    /**
     * 근거가 삭제 문서뿐인 Wiki를 AI 호출 전에 결정적으로 지웁니다. 상세는
     * {@link WikiTransformationApplier#pruneFullyDependentWikis}.
     *
     * <p>AI 하이드레이션이 별도 트랜잭션에서 조회 API 로 위키를 읽으므로, 프리패스 결과가
     * 그 조회에 보이려면 호출 전에 커밋돼 있어야 한다({@code REQUIRES_NEW}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WikiTransformationApplier.PruneResult pruneFullyDependentWikis(
            long documentId,
            String scopeKey,
            String originalFileName
    ) {
        return applier.pruneFullyDependentWikis(scopeKey, documentId, originalFileName);
    }

    private List<AiJob.AffectedWiki> affectedWikisOf(List<Long> affectedWikiIds) {
        if (wikiRepository == null || affectedWikiIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Wiki> wikisById = wikiRepository.findAllById(affectedWikiIds).stream()
                .collect(Collectors.toMap(Wiki::id, Function.identity()));
        return affectedWikiIds.stream()
                .map(wikisById::get)
                .filter(java.util.Objects::nonNull)
                .map(wiki -> new AiJob.AffectedWiki(wiki.id(), wiki.title()))
                .toList();
    }

    /**
     * @param referencingWikiCount 걷어내기 <b>전에</b> 이 문서를 근거로 삼던 Wiki 수입니다.
     *                             걷어내기가 아닌 반영에서는 0입니다(S15P11B106-225).
     */
    public record WikiTransformationResult(
            List<Long> affectedWikiIds,
            List<AiJob.AffectedWiki> affectedWikis,
            String summary,
            int referencingWikiCount
    ) {

        public WikiTransformationResult {
            affectedWikiIds = List.copyOf(affectedWikiIds);
            affectedWikis = List.copyOf(affectedWikis);
        }

        public WikiTransformationResult(List<Long> affectedWikiIds, String summary) {
            this(affectedWikiIds, List.of(), summary, 0);
        }

        public WikiTransformationResult(List<Long> affectedWikiIds, List<AiJob.AffectedWiki> affectedWikis, String summary) {
            this(affectedWikiIds, affectedWikis, summary, 0);
        }

        public WikiTransformationResult(List<Long> affectedWikiIds, String summary, int referencingWikiCount) {
            this(affectedWikiIds, List.of(), summary, referencingWikiCount);
        }
    }
}
