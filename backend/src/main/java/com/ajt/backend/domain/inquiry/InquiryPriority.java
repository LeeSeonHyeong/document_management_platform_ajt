package com.ajt.backend.domain.inquiry;

import java.util.Locale;

/**
 * 문의 우선순위입니다.
 * DB에는 대문자로 저장하고 API에서는 소문자 값(high/normal/low)으로 주고받습니다.
 */
public enum InquiryPriority {
    HIGH,
    NORMAL,
    LOW;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static InquiryPriority fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("우선순위는 필수 입력값입니다.");
        }

        try {
            return InquiryPriority.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("우선순위는 high, normal 또는 low만 사용할 수 있습니다.");
        }
    }
}
