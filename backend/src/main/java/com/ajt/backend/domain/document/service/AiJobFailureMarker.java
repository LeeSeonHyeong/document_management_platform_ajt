package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.repository.AiJobRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아직 시작하지 못한 AI 작업을 실패로 마감하는 컴포넌트입니다(S15P11B106-146).
 *
 * <p>파일 교체 확정(promote)이 트랜잭션 커밋 이후(afterCommit)에 실패하면, 이미 커밋된 작업(WAITING)을 FAILED로
 * 남겨 관리자가 실패 상태를 인지할 수 있게 한다. afterCommit 시점에는 활성 트랜잭션이 없으므로 새 트랜잭션
 * ({@code REQUIRES_NEW})에서 상태를 저장한다.
 */
@Component
public class AiJobFailureMarker {

    private final AiJobRepository aiJobRepository;

    public AiJobFailureMarker(AiJobRepository aiJobRepository) {
        this.aiJobRepository = aiJobRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailedBeforeStart(long jobId, String failureReason) {
        aiJobRepository.findById(jobId).ifPresent(job -> {
            job.failBeforeStart(failureReason);
            aiJobRepository.save(job);
        });
    }
}
