package com.ajt.backend.global.ai.client;

import java.util.List;

/**
 * 답변 1단계 응답입니다. AI가 고른 자료 ID로, Spring Boot가 권한을 다시 검사한 뒤 본문을 읽습니다.
 *
 * <p>{@code questionType}은 계약상 {@code wiki}·{@code schedule}·{@code mixed} 중 하나이며,
 * 종류별 최대 5개까지 고른다.
 */
public record AnswerContextSelectionResponse(
        String questionType,
        List<String> wikiIds,
        List<String> scheduleIds,
        String reason
) {
}
