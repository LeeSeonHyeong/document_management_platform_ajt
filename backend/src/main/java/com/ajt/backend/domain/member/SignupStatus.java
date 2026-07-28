package com.ajt.backend.domain.member;

import java.util.Locale;

public enum SignupStatus {
    PENDING,
    APPROVED,
    REJECTED;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SignupStatus fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("가입 상태는 필수 입력값입니다.");
        }

        try {
            return SignupStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("가입 상태는 pending, approved, rejected만 사용할 수 있습니다.");
        }
    }
}