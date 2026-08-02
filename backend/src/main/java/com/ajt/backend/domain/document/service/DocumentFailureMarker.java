package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.repository.DocumentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파일 교체 확정(promote)이 커밋 이후 실패했을 때 문서 자체를 실패로 남기는 컴포넌트입니다(S15P11B106-146).
 *
 * <p>promote 실패 시 DB에는 이미 새 파일 메타데이터가 커밋돼 있는데 실제 파일은 최종 경로에 없을 수 있다.
 * 이때 AiJob뿐 아니라 문서도 FAILED로 남겨 다운로드/상세 조회에서 정상인 것처럼 보이지 않게 한다.
 * afterCommit 시점에는 활성 트랜잭션이 없으므로 새 트랜잭션({@code REQUIRES_NEW})에서 상태를 저장한다.
 */
@Component
public class DocumentFailureMarker {

    private final DocumentRepository documentRepository;

    public DocumentFailureMarker(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long documentId, String failureReason) {
        documentRepository.findById(documentId).ifPresent(document -> {
            document.failReplace(failureReason);
            documentRepository.save(document);
        });
    }
}
