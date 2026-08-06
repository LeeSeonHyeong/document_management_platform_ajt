package com.ajt.backend.domain.document.api;

import java.util.List;

/** 생성·시작된 AI 작업 한 건입니다. status는 시작 직후이므로 processing이다. */
public record AiJobCreatedJob(
        String jobId,
        String scopeKey,
        String status,
        List<String> documentIds
) {
}
