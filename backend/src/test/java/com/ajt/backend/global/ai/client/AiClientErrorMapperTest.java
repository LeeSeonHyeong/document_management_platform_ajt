package com.ajt.backend.global.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class AiClientErrorMapperTest {

    private final AiClientErrorMapper mapper = new AiClientErrorMapper(new ObjectMapper());

    @Test
    void mapsBadRequestErrorBody() {
        AiClientException error = mapper.map(
                HttpStatus.BAD_REQUEST,
                """
                        {"status":400,"code":"INVALID_SOURCE_PARSE_REQUEST","message":"필수 값이 누락되었습니다.","fieldErrors":[]}
                        """
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.BAD_REQUEST);
        assertThat(error.upstreamStatus()).isEqualTo(400);
        assertThat(error.upstreamCode()).isEqualTo("INVALID_SOURCE_PARSE_REQUEST");
        assertThat(error.upstreamMessage()).isEqualTo("필수 값이 누락되었습니다.");
    }

    @Test
    void mapsUnauthorizedResponse() {
        AiClientException error = mapper.map(HttpStatus.UNAUTHORIZED, "{}");

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.UNAUTHORIZED);
        assertThat(error.upstreamStatus()).isEqualTo(401);
    }

    @Test
    void mapsServerErrorResponse() {
        AiClientException error = mapper.map(HttpStatus.INTERNAL_SERVER_ERROR, "{}");

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.SERVER_ERROR);
        assertThat(error.upstreamStatus()).isEqualTo(500);
    }

    @Test
    void mapsUnexpectedStatusResponse() {
        AiClientException error = mapper.map(HttpStatus.NOT_FOUND, "{}");

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.UNEXPECTED_STATUS);
        assertThat(error.upstreamStatus()).isEqualTo(404);
    }

    @ParameterizedTest
    @DisplayName("계약 1.8.0 의 챗봇 오류 이름 여덟 개를 사용자 안내로 옮긴다")
    @ValueSource(strings = {
            "INVALID_ANSWER_REQUEST", "NO_WIKI_OR_SCHEDULE_WAS_READ", "WIKI_QUERY_FAILED",
            "SCHEDULE_QUERY_FAILED", "AGENT_TURN_LIMIT_REACHED", "AGENT_TIMED_OUT",
            "MODEL_CALL_FAILED", "ANSWER_WAS_EMPTY"})
    void everyNewCodeIsMapped(String code) {
        assertThat(AiClientErrorMapper.messageFor(code)).isNotBlank();
    }

    @Test
    @DisplayName("모르는 이름과 이름 없는 실패도 사용자에게는 같은 안내로 나간다")
    void unknownCodeStillHasAMessage() {
        assertThat(AiClientErrorMapper.messageFor("SOMETHING_NEW")).contains("처리 중 문제");
        assertThat(AiClientErrorMapper.messageFor(null)).contains("처리 중 문제");
        assertThat(AiClientErrorMapper.messageFor("  ")).contains("처리 중 문제");
    }

    @ParameterizedTest
    @DisplayName("고칠 수 있는 실패만 재시도 대상이다")
    @ValueSource(strings = {
            "AGENT_TIMED_OUT", "AGENT_TURN_LIMIT_REACHED", "MODEL_CALL_FAILED",
            "WIKI_QUERY_FAILED", "SCHEDULE_QUERY_FAILED", "ANSWER_WAS_EMPTY"})
    void retryableCodesAreRecognised(String code) {
        assertThat(AiClientErrorMapper.isRetryableAnswerFailure(code)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("다시 해도 같은 실패와 모르는 이름은 재시도하지 않는다 — 대기만 두 배가 된다")
    @ValueSource(strings = {
            "INVALID_ANSWER_REQUEST", "NO_WIKI_OR_SCHEDULE_WAS_READ", "SOMETHING_NEW"})
    void deterministicAndUnknownCodesAreNotRetryable(String code) {
        assertThat(AiClientErrorMapper.isRetryableAnswerFailure(code)).isFalse();
    }

    @Test
    @DisplayName("이름이 없는 실패는 재시도하지 않는다")
    void missingCodeIsNotRetryable() {
        assertThat(AiClientErrorMapper.isRetryableAnswerFailure(null)).isFalse();
    }

    @Test
    void keepsStatusWhenErrorBodyIsMalformed() {
        AiClientException error = mapper.map(HttpStatus.BAD_GATEWAY, "not-json");

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.SERVER_ERROR);
        assertThat(error.upstreamStatus()).isEqualTo(502);
        assertThat(error.upstreamCode()).isNull();
        assertThat(error.upstreamMessage()).isNull();
    }
}
