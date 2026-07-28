package com.ajt.backend.domain.member;

import java.util.Locale;

public enum SignupStatus {
    PENDING,
    APPROVED,
    REJECTED;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
