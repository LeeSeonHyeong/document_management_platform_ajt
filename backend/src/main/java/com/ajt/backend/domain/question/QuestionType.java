package com.ajt.backend.domain.question;

import java.util.Locale;

/**
 * 질문 유형입니다. AI가 질문 맥락을 보고 자동 판단하며(FR-QNA-002), 판단 전에는 NULL일 수 있습니다.
 */
public enum QuestionType {
    WIKI,
    SCHEDULE,
    MIXED;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static QuestionType fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("질문 유형은 필수 입력값입니다.");
        }
        try {
            return QuestionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("질문 유형은 wiki, schedule 또는 mixed만 사용할 수 있습니다.");
        }
    }
}
