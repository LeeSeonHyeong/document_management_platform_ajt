package com.ajt.backend.global.error;

public record FieldErrorResponse(
        String field,
        String reason
) {
}