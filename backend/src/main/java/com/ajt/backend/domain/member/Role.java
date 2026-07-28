package com.ajt.backend.domain.member;

import java.util.Locale;

public enum Role {
    EMPLOYEE,
    ADMIN;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("역할은 필수 입력값입니다.");
        }

        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("역할은 employee 또는 admin만 사용할 수 있습니다.");
        }
    }
}