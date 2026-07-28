package com.ajt.backend.global.ai.client;

import com.ajt.backend.global.error.FieldErrorResponse;
import java.util.List;

public record AiApiErrorResponse(
        Integer status,
        String error,
        String code,
        String message,
        String path,
        List<FieldErrorResponse> fieldErrors
) {
}
