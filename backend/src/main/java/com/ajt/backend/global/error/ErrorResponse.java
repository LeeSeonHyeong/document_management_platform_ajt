package com.ajt.backend.global.error;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldErrorResponse> fieldErrors
) {
    public static ErrorResponse of(ErrorCode errorCode, String path) {
        return of(errorCode, errorCode.message(), path, List.of());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, String path) {
        return of(errorCode, message, path, List.of());
    }

    public static ErrorResponse of(
            ErrorCode errorCode,
            String message,
            String path,
            List<FieldErrorResponse> fieldErrors
    ) {
        return new ErrorResponse(
                Instant.now(),
                errorCode.status().value(),
                errorCode.status().getReasonPhrase(),
                errorCode.name(),
                message,
                path,
                fieldErrors
        );
    }
}