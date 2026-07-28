package com.ajt.backend.global.ai.client;

import com.ajt.backend.global.error.FieldErrorResponse;
import java.util.List;

public class AiClientException extends RuntimeException {

    private final AiClientFailureType failureType;
    private final Integer upstreamStatus;
    private final String upstreamCode;
    private final String upstreamMessage;
    private final List<FieldErrorResponse> fieldErrors;

    public AiClientException(
            AiClientFailureType failureType,
            Integer upstreamStatus,
            String upstreamCode,
            String upstreamMessage,
            List<FieldErrorResponse> fieldErrors,
            Throwable cause
    ) {
        super("AI client failure: " + failureType + " (status=" + upstreamStatus + ")", cause);
        this.failureType = failureType;
        this.upstreamStatus = upstreamStatus;
        this.upstreamCode = upstreamCode;
        this.upstreamMessage = upstreamMessage;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public AiClientFailureType failureType() {
        return failureType;
    }

    public Integer upstreamStatus() {
        return upstreamStatus;
    }

    public String upstreamCode() {
        return upstreamCode;
    }

    public String upstreamMessage() {
        return upstreamMessage;
    }

    public List<FieldErrorResponse> fieldErrors() {
        return fieldErrors;
    }
}
