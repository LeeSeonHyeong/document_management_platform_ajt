package com.ajt.backend.domain.question.dto;

import com.ajt.backend.domain.question.AnswerSource;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 답변 출처 응답입니다. type이 wiki면 wikiId, schedule이면 scheduleId만 채워집니다.
 * 계약과 맞추기 위해 null인 반대쪽 id는 응답 JSON에서 제외한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionSourceResponse(
        String type,
        String wikiId,
        String scheduleId,
        String title
) {
    public static QuestionSourceResponse from(AnswerSource source) {
        boolean wiki = source.getWikiId() != null;
        return new QuestionSourceResponse(
                wiki ? "wiki" : "schedule",
                source.getWikiId() == null ? null : String.valueOf(source.getWikiId()),
                source.getScheduleId() == null ? null : String.valueOf(source.getScheduleId()),
                source.getSourceTitle()
        );
    }
}
