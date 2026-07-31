package com.ajt.backend.global.ai.client;

import com.ajt.backend.global.error.FieldErrorResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.ObjectMapper;

public final class AiClientErrorMapper {

    /**
     * 계약 1.8.0 의 챗봇 오류 이름별 사용자 안내입니다. (설계 §6.7)
     *
     * <p>모르는 이름은 여기 없어도 {@link #messageFor} 가 같은 기본 안내로 떨어뜨린다 — AI 가
     * 이름을 늘려도 사용자 화면이 깨지지 않는다.
     */
    private static final Map<String, String> ANSWER_FAILURE_MESSAGES = Map.of(
            "INVALID_ANSWER_REQUEST", "답변 요청을 처리 중 문제가 생겼습니다. 잠시 후 다시 시도해주세요.",
            "NO_WIKI_OR_SCHEDULE_WAS_READ", "답변 근거를 읽지 못해 처리 중 문제가 생겼습니다. 잠시 후 다시 시도해주세요.",
            "WIKI_QUERY_FAILED", "위키 자료를 읽는 중 문제가 생겼습니다. 잠시 후 다시 시도해주세요.",
            "SCHEDULE_QUERY_FAILED", "일정을 읽는 중 문제가 생겼습니다. 잠시 후 다시 시도해주세요.",
            "AGENT_TURN_LIMIT_REACHED", "답변을 만드는 과정이 길어져 마치지 못했습니다. 질문을 나눠서 다시 물어봐주세요.",
            "AGENT_TIMED_OUT", "답변 생성이 제한 시간을 넘었습니다. 잠시 후 다시 시도해주세요.",
            "MODEL_CALL_FAILED", "답변 생성 중 문제가 생겼습니다. 잠시 후 다시 시도해주세요.",
            "ANSWER_WAS_EMPTY", "답변을 만들지 못했습니다. 질문을 조금 더 구체적으로 다시 물어봐주세요."
    );

    /** 계약에 없는 이름과 이름이 아예 없는 실패(전송 오류 등)에 쓰는 안내입니다. */
    private static final String DEFAULT_ANSWER_FAILURE_MESSAGE =
            "답변을 처리 중 문제가 생겼습니다. 잠시 후 다시 시도해주세요.";

    /**
     * 다시 부르면 달라질 수 있는 실패입니다. 요청 형식 오류와 「조회를 아예 하지 않았다」는 같은
     * 요청에 같은 결과이므로 대기 시간만 두 배가 된다 (설계 §6.8).
     *
     * <p><b>모르는 이름도 재시도하지 않는다.</b> AI 가 이름을 늘렸을 때 조용히 대기 시간을 두 배로
     * 만드는 것보다, 한 번 실패하고 그 이름을 계약에 넣는 편이 낫다. 이름이 없는 전송 실패
     * (읽기 타임아웃 등)도 여기 걸리지 않는다 — 사용자는 이미 그만큼 기다린 상태다.
     */
    private static final Set<String> RETRYABLE_ANSWER_FAILURES = Set.of(
            "AGENT_TIMED_OUT",
            "AGENT_TURN_LIMIT_REACHED",
            "MODEL_CALL_FAILED",
            "WIKI_QUERY_FAILED",
            "SCHEDULE_QUERY_FAILED",
            "ANSWER_WAS_EMPTY"
    );

    private final ObjectMapper objectMapper;

    AiClientErrorMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 오류 이름에 대응하는 사용자 안내입니다. 모르는 이름도 빈 값 없이 항상 안내가 나간다. */
    public static String messageFor(String code) {
        if (code == null || code.isBlank()) {
            return DEFAULT_ANSWER_FAILURE_MESSAGE;
        }
        return ANSWER_FAILURE_MESSAGES.getOrDefault(code, DEFAULT_ANSWER_FAILURE_MESSAGE);
    }

    /** 한 번 더 불러볼 만한 실패인지입니다. */
    public static boolean isRetryableAnswerFailure(String code) {
        return code != null && RETRYABLE_ANSWER_FAILURES.contains(code);
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
