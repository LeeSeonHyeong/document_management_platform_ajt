package com.ajt.backend.global.ai.client;

import com.ajt.backend.global.error.FieldErrorResponse;
import java.util.List;

/**
 * FastAPI 내부 API의 공통 오류 응답입니다.
 *
 * <p>{@code failureStage}는 Wiki 변환·문맥 선택 오류에만 선택적으로 실려 옵니다.
 * 계약 값은 {@code context_load}, {@code agent_timeout}, {@code agent_error}, {@code lint_failed}, {@code assemble}이며,
 * 계약에 없는 값이 와도 역직렬화가 깨지지 않도록 문자열로 그대로 다룹니다.
 */
public record AiApiErrorResponse(
        Integer status,
        String error,
        String code,
        String message,
        String path,
        List<FieldErrorResponse> fieldErrors,
        String failureStage
) {
}
