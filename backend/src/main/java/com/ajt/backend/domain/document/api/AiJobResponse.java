package com.ajt.backend.domain.document.api;

import java.time.Instant;
import java.util.List;

public record AiJobResponse(
        String jobId,
        String status,
        List<DocumentResultResponse> documentResults,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String failureReason
) {

    /**
     * @param currentStage 지금 어디까지 왔는지. 문서 상태에서 역산하므로 <b>실패 지점이
     *                     아니다</b> — 실패한 문서는 어디서 실패했든 {@code parsing}이 된다.
     * @param failureStage 실제로 어디서 실패했는지. AI 오류 응답이 실어 준 값을
     *                     {@code ai_job.document_results}에 기록해 둔 것이며,
     *                     실패하지 않았거나 단계를 알 수 없으면 {@code null}이다.
     */
    /**
     * @param originalFileName 그때 그 파일 이름의 스냅샷(S15P11B106-202). 문서가 하드 삭제돼도
     *                         이력에 무엇이 바뀌었는지 남기기 위한 것이라, 문서 조회로 채우지
     *                         않고 작업 결과에 기록된 값을 그대로 내려준다. 이 필드가 생기기
     *                         전 작업은 {@code null}이며, 그때는 문서가 살아 있으면 현재
     *                         이름으로 메운다.
     */
    public record DocumentResultResponse(
            String documentId,
            String originalFileName,
            int order,
            String status,
            String currentStage,
            String summary,
            String failureReason,
            String failureStage
    ) {
    }
}
