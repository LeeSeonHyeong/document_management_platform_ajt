package com.ajt.backend.domain.schedule.model;

import java.util.Locale;

public enum ScheduleStatus {
    DRAFT,
    APPROVED;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ScheduleStatus fromApiValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("일정 상태를 입력해주세요.");
        }
        for (ScheduleStatus status : values()) {
            if (status.apiValue().equals(value.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("허용되지 않는 일정 상태입니다: " + value);
    }
}
