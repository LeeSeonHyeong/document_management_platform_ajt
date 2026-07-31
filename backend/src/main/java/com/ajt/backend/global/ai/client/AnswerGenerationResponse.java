package com.ajt.backend.global.ai.client;

import java.util.List;

/**
 * 챗봇 답변 응답입니다. Spring Boot가 {@code ai_answer}·{@code answer_source}·{@code question}에
 * 저장합니다.
 *
 * <p>출처 한 건은 {@code wikiId}와 {@code scheduleId} 중 한쪽만 갖는다 —
 * {@code type}이 어느 쪽인지 알려준다.
 *
 * <p><b>근거를 못 찾은 답변도 정상이다.</b> 에이전트가 찾아봤지만 근거가 없으면 {@code sources}가
 * 빈 배열이고 {@code answer}는 정보가 부족하다는 안내다 (FR-QNA-007). 오류가 아니므로 그대로
 * 저장하고 사용자에게 보여준다 — {@code answer_source} 행이 0건이 된다. 조회를 <b>아예 시도하지
 * 않은</b> 실행만 {@code NO_WIKI_OR_SCHEDULE_WAS_READ} 실패로 온다.
 */
public record AnswerGenerationResponse(
        String answer,
        List<Source> sources,
        /*
         * 수정(S15P11B106-169): 1단계 응답이던 값이 여기로 옮겨왔다. 에이전트가 질문 맥락을 보고
         * 판단한다 (FR-QNA-002). 「읽은 자료의 종류」가 아니다 — 일정 질문을 Wiki 공지로 답하는
         * 경우가 있어 결과로 정하면 유형이 뒤집힌다.
         */
        String questionType
) {
    /**
     * 답변에 사용한 출처입니다.
     *
     * <p>{@code title}은 필수다 — {@code answer_source.source_title}이 {@code NOT NULL}이고,
     * 에이전트가 <b>읽은 기록에 있는 제목을 그대로</b> 보낸다(모델이 다시 쓴 제목이 아니다).
     */
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
