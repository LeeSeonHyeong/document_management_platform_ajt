package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.model.AiJob;

public interface DocumentParseJobLauncher {

    /** 문서를 새로 반영하는 기본 실행입니다. (업로드·재시도) */
    default void launch(AiJob job) {
        launch(job, DocumentReprocessPlan.added());
    }

    void launch(AiJob job, DocumentReprocessPlan plan);

    /**
     * 트랜잭션 동기화(afterCommit)에 의존하지 않고 즉시 실행합니다(S15P11B106-146).
     * 파일 교체 확정(promote)이 커밋 이후 afterCommit 안에서 성공했을 때, 그 자리에서 재처리를 시작하기 위해 사용한다.
     * (afterCommit 안에서 {@link #launch}를 부르면 새로 등록한 동기화의 afterCommit이 실행되지 않으므로 이 경로가 필요하다.)
     */
    void launchNow(AiJob job, DocumentReprocessPlan plan);
}
