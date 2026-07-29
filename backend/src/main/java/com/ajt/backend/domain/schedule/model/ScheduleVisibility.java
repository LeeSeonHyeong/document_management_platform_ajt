package com.ajt.backend.domain.schedule.model;

import java.util.Locale;

public enum ScheduleVisibility {
    ALL,
    DEPARTMENT,
    PERSONAL;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ScheduleVisibility fromApiValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("공개 범위를 입력해주세요.");
        }
        for (ScheduleVisibility visibility : values()) {
            if (visibility.apiValue().equals(value.trim())) {
                return visibility;
            }
        }
        throw new IllegalArgumentException("허용되지 않는 공개 범위입니다: " + value);
    }
}
