package com.ajt.backend.global.ai.client;

public enum AiClientFailureType {
    BAD_REQUEST,
    UNAUTHORIZED,
    SERVER_ERROR,
    UNEXPECTED_STATUS,
    CONNECTION_FAILED,
    TIMEOUT,
    INVALID_RESPONSE;

    /**
     * AI 서버에 아예 닿지 못했거나 응답을 받지 못한 "일시적 이용 불가"인지입니다.
     *
     * <p>연결 실패({@link #CONNECTION_FAILED})와 타임아웃({@link #TIMEOUT})만 해당한다 —
     * 이 경우 사용자-facing API는 500이 아니라 503(AI_SERVER_UNAVAILABLE)으로 내려 "잠시 후
     * 다시 시도"를 안내한다. AI가 응답은 했으나 5xx/오류 본문을 준 경우({@link #SERVER_ERROR}
     * 등)는 "AI 처리 실패"이므로 각 도메인의 기존 오류를 유지한다(구분).
     */
    public boolean isServerUnavailable() {
        return this == CONNECTION_FAILED || this == TIMEOUT;
    }
}
