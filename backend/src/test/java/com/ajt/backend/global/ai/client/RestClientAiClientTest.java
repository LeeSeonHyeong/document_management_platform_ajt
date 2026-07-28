package com.ajt.backend.global.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class RestClientAiClientTest {

    private MockRestServiceServer server;
    private RestClientAiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://localhost:8000")
                .defaultHeader("X-Internal-API-Key", "local-dev-key");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientAiClient(builder.build(), new ObjectMapper());
    }

    @Test
    @DisplayName("Wiki 문맥 선택 요청을 JSON 계약대로 보내고 선택된 Wiki ID를 읽는다")
    void sendsWikiContextSelectionContractAndReadsPostmanSuccess() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-context-selections"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-API-Key", "local-dev-key"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(content().json("""
                        {
                          "jobId": "42",
                          "documentId": "15",
                          "scopeKey": "D1-D2",
                          "changeType": "document_added",
                          "parsedMarkdown": "# 취업 규칙\\n본문...",
                          "currentIndex": "# 목차\\n- [휴가 규정](pages/101.md)"
                        }
                        """))
                .andRespond(withSuccess("""
                        {"wikiIds":["101","108"],"reason":"새 취업 규칙의 휴가·복무 항목과 관련된 현재 Wiki입니다."}
                        """, MediaType.APPLICATION_JSON));

        WikiContextSelectionResponse response = client.selectWikiContext(new WikiContextSelectionRequest(
                "42",
                "15",
                "D1-D2",
                WikiDocumentChangeType.DOCUMENT_ADDED,
                "# 취업 규칙\n본문...",
                null,
                "# 목차\n- [휴가 규정](pages/101.md)"
        ));

        assertThat(response.wikiIds()).containsExactly("101", "108");
        assertThat(response.reason()).isEqualTo("새 취업 규칙의 휴가·복무 항목과 관련된 현재 Wiki입니다.");
        server.verify();
    }

    @Test
    @DisplayName("Wiki 문맥 선택 응답의 wikiIds가 5개를 초과하면 잘못된 응답으로 처리한다")
    void mapsTooManyWikiContextSelectionIdsToInvalidResponse() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-context-selections"))
                .andRespond(withSuccess("""
                        {"wikiIds":["1","2","3","4","5","6"],"reason":"too many"}
                        """, MediaType.APPLICATION_JSON));

        AiClientException error = catchThrowableOfType(
                () -> client.selectWikiContext(new WikiContextSelectionRequest(
                        "42",
                        "15",
                        "ALL",
                        WikiDocumentChangeType.DOCUMENT_ADDED,
                        "# parsed",
                        null,
                        "# index"
                )),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.INVALID_RESPONSE);
        assertThat(error.upstreamStatus()).isEqualTo(200);
    }

    @Test
    void sendsExactMultipartContractAndReadsPostmanSuccess() {
        server.expect(requestTo("http://localhost:8000/internal/v1/source-parses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-API-Key", "local-dev-key"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, containsString(MediaType.MULTIPART_FORM_DATA_VALUE)))
                .andExpect(content().string(containsString("name=\"requestId\"")))
                .andExpect(content().string(containsString("name=\"sourceType\"")))
                .andExpect(content().string(containsString("name=\"sourceId\"")))
                .andExpect(content().string(containsString("name=\"file\"; filename=\"agenda.pdf\"")))
                .andExpect(content().string(containsString("name=\"originalFileName\"")))
                .andExpect(content().string(containsString("name=\"mimeType\"")))
                .andRespond(withSuccess("""
                        {"requestId":"req-001","sourceType":"schedule","sourceId":"schedule-source-001","parsedMarkdown":"# 회의록","warnings":[]}
                        """, MediaType.APPLICATION_JSON));

        SourceParseResponse response = client.parseSource(TestSourceParseRequestFactory.create());

        assertThat(response.requestId()).isEqualTo("req-001");
        assertThat(response.sourceType()).isEqualTo(SourceType.SCHEDULE);
        assertThat(response.sourceId()).isEqualTo("schedule-source-001");
        assertThat(response.parsedMarkdown()).isEqualTo("# 회의록");
        assertThat(response.warnings()).isEmpty();
        server.verify();
    }

    @Test
    void mapsFastApiBadRequestToClientException() {
        server.expect(requestTo("http://localhost:8000/internal/v1/source-parses"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"status":400,"code":"INVALID_SOURCE_PARSE_REQUEST","message":"필수 값이 누락되었습니다.","fieldErrors":[]}
                                """));

        AiClientException error = catchThrowableOfType(
                () -> client.parseSource(TestSourceParseRequestFactory.create()),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.BAD_REQUEST);
        assertThat(error.upstreamStatus()).isEqualTo(400);
        assertThat(error.upstreamCode()).isEqualTo("INVALID_SOURCE_PARSE_REQUEST");
    }

    @Test
    void mapsMissingSuccessFieldsToInvalidResponse() {
        server.expect(requestTo("http://localhost:8000/internal/v1/source-parses"))
                .andRespond(withSuccess("{\"requestId\":\"req-001\"}", MediaType.APPLICATION_JSON));

        AiClientException error = catchThrowableOfType(
                () -> client.parseSource(TestSourceParseRequestFactory.create()),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.INVALID_RESPONSE);
        assertThat(error.upstreamStatus()).isEqualTo(200);
    }

    @Test
    void mapsSocketTimeoutToTimeout() {
        RestClientAiClient timeoutClient = clientWithFailure(new SocketTimeoutException());

        AiClientException error = catchThrowableOfType(
                () -> timeoutClient.parseSource(TestSourceParseRequestFactory.create()),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.TIMEOUT);
        assertThat(error.getCause()).isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void mapsConnectionFailureToConnectionFailed() {
        RestClientAiClient connectionClient = clientWithFailure(new ConnectException());

        AiClientException error = catchThrowableOfType(
                () -> connectionClient.parseSource(TestSourceParseRequestFactory.create()),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.CONNECTION_FAILED);
        assertThat(error.getCause()).isInstanceOf(ResourceAccessException.class);
    }

    private RestClientAiClient clientWithFailure(IOException cause) {
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:8000")
                .requestFactory((uri, method) -> {
                    throw new ResourceAccessException("AI request failed", cause);
                })
                .build();
        return new RestClientAiClient(restClient, new ObjectMapper());
    }
}
