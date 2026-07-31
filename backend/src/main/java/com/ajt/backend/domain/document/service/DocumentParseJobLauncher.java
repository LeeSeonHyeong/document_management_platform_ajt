package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.model.AiJob;

public interface DocumentParseJobLauncher {

    /** 문서를 새로 반영하는 기본 실행입니다. (업로드·재시도) */
    default void launch(AiJob job) {
        launch(job, DocumentReprocessPlan.added());
    }

    void launch(AiJob job, DocumentReprocessPlan plan);
}
