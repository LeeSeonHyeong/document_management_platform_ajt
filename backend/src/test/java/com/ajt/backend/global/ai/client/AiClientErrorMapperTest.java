package com.ajt.backend.global.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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

    @Test
    void keepsStatusWhenErrorBodyIsMalformed() {
        AiClientException error = mapper.map(HttpStatus.BAD_GATEWAY, "not-json");

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.SERVER_ERROR);
        assertThat(error.upstreamStatus()).isEqualTo(502);
        assertThat(error.upstreamCode()).isNull();
        assertThat(error.upstreamMessage()).isNull();
    }
}
