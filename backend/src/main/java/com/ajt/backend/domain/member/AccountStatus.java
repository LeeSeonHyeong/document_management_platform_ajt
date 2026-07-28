package com.ajt.backend.domain.member;

import java.util.Locale;

public enum AccountStatus {
    ACTIVE,
    INACTIVE;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AccountStatus fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("계정 상태는 필수 입력값입니다.");
        }

        try {
            return AccountStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("계정 상태는 active 또는 inactive만 사용할 수 있습니다.");
        }
    }
}