package com.ajt.backend.global.ai.client;

import com.ajt.backend.global.error.FieldErrorResponse;
import java.util.List;
import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.ObjectMapper;

final class AiClientErrorMapper {

    private final ObjectMapper objectMapper;

    AiClientErrorMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AiClientException map(HttpStatusCode statusCode, String responseBody) {
        AiApiErrorResponse errorResponse = readErrorResponse(responseBody);

        return new AiClientException(
                classify(statusCode.value()),
                statusCode.value(),
                errorResponse == null ? null : errorResponse.code(),
                errorResponse == null ? null : errorResponse.message(),
                errorResponse == null ? List.<FieldErrorResponse>of() : errorResponse.fieldErrors(),
                null,
                errorResponse == null ? null : errorResponse.failureStage()
        );
    }

    private AiApiErrorResponse readErrorResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(responseBody, AiApiErrorResponse.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private AiClientFailureType classify(int status) {
        if (status == 400) {
            return AiClientFailureType.BAD_REQUEST;
        }
        if (status == 401) {
            return AiClientFailureType.UNAUTHORIZED;
        }
        if (status >= 500) {
            return AiClientFailureType.SERVER_ERROR;
        }
        return AiClientFailureType.UNEXPECTED_STATUS;
    }
}
