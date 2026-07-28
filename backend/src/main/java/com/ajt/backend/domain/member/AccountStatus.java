package com.ajt.backend.domain.member;

import java.util.Locale;

public enum AccountStatus {
    ACTIVE,
    INACTIVE;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
