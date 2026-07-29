package com.ajt.backend.global.ai.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum WikiDocumentChangeType {
    DOCUMENT_ADDED("document_added"),
    DOCUMENT_REMOVED("document_removed"),
    DOCUMENT_REPLACED("document_replaced");

    private final String value;

    WikiDocumentChangeType(String value) {
        this.value = value;
    }

    @JsonCreator
    public static WikiDocumentChangeType fromValue(String value) {
        return Arrays.stream(values())
                .filter(changeType -> changeType.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported wiki document change type: " + value));
    }

    @JsonValue
    public String value() {
        return value;
    }
}
