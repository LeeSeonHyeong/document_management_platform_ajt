package com.ajt.backend.global.ai.client;

import com.ajt.backend.global.error.FieldErrorResponse;
import java.util.List;

public class AiClientException extends RuntimeException {

    private final AiClientFailureType failureType;
    private final Integer upstreamStatus;
    private final String upstreamCode;
    private final String upstreamMessage;
    private final List<FieldErrorResponse> fieldErrors;
    private final String failureStage;

    public AiClientException(
            AiClientFailureType failureType,
            Integer upstreamStatus,
            String upstreamCode,
            String upstreamMessage,
            List<FieldErrorResponse> fieldErrors,
            Throwable cause
    ) {
        this(failureType, upstreamStatus, upstreamCode, upstreamMessage, fieldErrors, cause, null);
    }

    public AiClientException(
            AiClientFailureType failureType,
            Integer upstreamStatus,
            String upstreamCode,
            String upstreamMessage,
            List<FieldErrorResponse> fieldErrors,
            Throwable cause,
            String failureStage
    ) {
        super("AI client failure: " + failureType + " (status=" + upstreamStatus + ")", cause);
        this.failureType = failureType;
        this.upstreamStatus = upstreamStatus;
        this.upstreamCode = upstreamCode;
        this.upstreamMessage = upstreamMessage;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        this.failureStage = failureStage;
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

    /**
     * Wiki 변환·문맥 선택 실패 단계입니다. 오류 응답에 실려 오지 않았으면 {@code null}입니다.
     * ai_job.document_results의 문서별 실패 단계로 저장합니다.
     */
    public String failureStage() {
        return failureStage;
    }
}
