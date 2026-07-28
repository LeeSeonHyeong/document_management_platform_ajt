package com.ajt.backend.global.ai.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum SourceType {
    WIKI("wiki"),
    SCHEDULE("schedule");

    private final String value;

    SourceType(String value) {
        this.value = value;
    }

    @JsonCreator
    public static SourceType fromValue(String value) {
        return Arrays.stream(values())
                .filter(sourceType -> sourceType.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported source type: " + value));
    }

    @JsonValue
    public String value() {
        return value;
    }
}
