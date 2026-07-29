package com.ajt.backend.domain.inquiry;

import java.util.Locale;

/**
 * 문의 처리 상태입니다.
 * 답변이 등록되면 DONE, 답변이 삭제되면 PENDING으로 되돌립니다.
 */
public enum InquiryStatus {
    PENDING,
    DONE;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static InquiryStatus fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("상태는 필수 입력값입니다.");
        }

        try {
            return InquiryStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("상태는 pending 또는 done만 사용할 수 있습니다.");
        }
    }
}
