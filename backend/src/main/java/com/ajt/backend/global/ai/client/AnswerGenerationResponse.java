package com.ajt.backend.global.ai.client;

import java.util.List;

/**
 * 답변 2단계 응답입니다. 답변 본문과 출처 배열로, Spring Boot가 {@code ai_answer}·
 * {@code answer_source}에 저장합니다.
 *
 * <p>출처 한 건은 {@code wikiId}와 {@code scheduleId} 중 한쪽만 갖는다 —
 * {@code type}이 어느 쪽인지 알려준다. (FastAPI가 안 쓰는 쪽을 아예 싣지 않는다)
 */
public record AnswerGenerationResponse(
        String answer,
        List<Source> sources
) {
    public record Source(
            String type,
            String wikiId,
            String scheduleId,
            String title
    ) {
        public boolean isWiki() {
            return "wiki".equals(type);
        }

        public boolean isSchedule() {
            return "schedule".equals(type);
        }
    }
}
