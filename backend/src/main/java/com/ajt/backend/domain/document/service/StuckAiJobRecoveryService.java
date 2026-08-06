package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서버가 내려가면서 중단된 AI 작업을 기동 시 한 번 실패로 마감합니다(S15P11B106-294).
 *
 * <p>작업은 WAITING → PROCESSING → COMPLETED/FAILED 로 흐른다. PROCESSING 은 워커 스레드가
 * 메모리에서 들고 있는 상태이므로, 그 도중 프로세스가 죽으면 <b>이어받을 주체가 없다</b>.
 * 재시작해도 상태를 되돌리는 코드가 없어 그 작업은 영구히 PROCESSING 으로 남았다.
 *
 * <p>남은 작업은 두 가지를 망가뜨린다.
 * <ul>
 *   <li>그 작업에 속한 문서를 수정·삭제할 수 없다 — 진행 중 작업의 문서는 거부한다
 *       (S15P11B106-286). 관리자가 손쓸 방법이 없어 DB 를 직접 고쳐야 풀렸다.</li>
 *   <li>문서도 PARSING·PROCESSING 에 굳는다. 재시도는 FAILED·CANCELLED 만 허용하므로
 *       ({@code Document.retryParsing}) 다시 돌릴 수도 없었다.</li>
 * </ul>
 *
 * <p>그래서 작업과 문서를 함께 실패로 내린다. 실패한 문서는 「AI 작업 요약」에서 사유를 보고
 * 재시도할 수 있다. 이어서 돌리지 않고 실패로 두는 이유는, 중단 지점을 알 수 없어 어디까지
 * Wiki 에 반영됐는지 확신할 수 없기 때문이다 — 관리자가 보고 다시 시작하는 편이 안전하다.
 *
 * <p>기동 시점에는 워커가 아직 없으므로 정상 진행 중인 작업을 잘못 건드릴 수 없다. 대상이 없으면
 * 아무 일도 하지 않는다.
 */
@Service
public class StuckAiJobRecoveryService {

    static final String FAILURE_REASON = "서버가 재시작되어 작업이 중단되었습니다. 다시 시도해주세요.";

    private static final Logger log = LoggerFactory.getLogger(StuckAiJobRecoveryService.class);

    private final AiJobRepository aiJobRepository;
    private final DocumentRepository documentRepository;

    public StuckAiJobRecoveryService(
            AiJobRepository aiJobRepository,
            DocumentRepository documentRepository
    ) {
        this.aiJobRepository = aiJobRepository;
        this.documentRepository = documentRepository;
    }

    /** 마감한 작업 수를 반환합니다. */
    @Transactional
    public int failInterruptedJobs() {
        List<AiJob> interrupted = aiJobRepository.findAllByStatusIn(List.of(AiJobStatus.PROCESSING));
        if (interrupted.isEmpty()) {
            return 0;
        }

        for (AiJob job : interrupted) {
            job.fail(FAILURE_REASON);
            aiJobRepository.save(job);
            releaseDocuments(job);
            log.warn("중단된 AI 작업을 실패로 마감했습니다: jobId={}, scopeKey={}, 문서 {}건",
                    job.id(), job.scopeKey(), job.documentIds().size());
        }
        return interrupted.size();
    }

    /**
     * 작업에 속한 문서 중 처리 중에 굳은 것을 실패로 내립니다.
     *
     * <p>이미 결과가 남은 문서(완료·실패)는 건드리지 않는다 — 작업이 중단되기 전에 끝난 문서다.
     * 삭제 대기(DELETING)도 두는데, 걷어내기 재시도 경로가 따로 있고 여기서 실패로 바꾸면
     * 삭제 요청이 조용히 사라진다.
     */
    private void releaseDocuments(AiJob job) {
        for (Document document : documentRepository.findAllById(job.documentIds())) {
            if (document.status() == DocumentStatus.PARSING) {
                document.failParsing(FAILURE_REASON);
            } else if (document.status() == DocumentStatus.PROCESSING) {
                document.failProcessing(FAILURE_REASON);
            }
        }
    }
}
