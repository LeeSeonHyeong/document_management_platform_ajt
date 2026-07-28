package com.ajt.backend.domain.member;

import java.util.Locale;

public enum Role {
    EMPLOYEE,
    ADMIN;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
