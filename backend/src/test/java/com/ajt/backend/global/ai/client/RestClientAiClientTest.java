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
import java.time.Instant;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.util.List;
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
    @DisplayName("Wiki 변환 요청을 JSON 계약대로 보내고 변경안을 읽는다")
    void sendsWikiTransformationContractAndReadsPostmanSuccess() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-transformations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-API-Key", "local-dev-key"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(content().json("""
                        {
                          "jobId": "42",
                          "documentId": "15",
                          "scopeKey": "D1-D2",
                          "changeType": "document_replaced",
                          "parsedMarkdown": "# 취업 규칙\\n본문...",
                          "removedParsedMarkdown": "# 기존 취업 규칙\\n이전 본문...",
                          "currentIndex": "# 목차\\n- [휴가 규정](pages/101.md)",
                          "currentCategories": [
                            {"categoryId": "10", "name": "인사·복무"}
                          ],
                          "selectedWikis": [
                            {
                              "wikiId": "101",
                              "categoryId": "10",
                              "title": "휴가 규정",
                              "summary": "연차와 반차 사용 기준",
                              "contentMarkdown": "# 휴가 규정\\n...",
                              "documentRefs": ["15", "18"],
                              "wikiRefs": ["108"]
                            }
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "summary": "휴가 규정 Wiki를 생성했습니다.",
                          "categoryChanges": [
                            {
                              "action": "create",
                              "tempCategoryId": "category-temp-1",
                              "name": "휴가 및 근태"
                            }
                          ],
                          "wikiChanges": [
                            {
                              "action": "create",
                              "tempWikiId": "wiki-temp-1",
                              "title": "휴가 규정",
                              "contentMarkdown": "# 휴가 규정\\n...",
                              "evidence": [
                                {
                                  "documentId": "15",
                                  "footnote": "1",
                                  "location": "3장 휴가",
                                  "quote": "연차는 15일을 부여한다"
                                }
                              ]
                            }
                          ],
                          "relationChanges": [],
                          "indexEntries": [
                            {
                              "wikiRef": "wiki-temp-1",
                              "order": 1,
                              "title": "휴가 규정",
                              "summary": "연차와 반차 사용 기준"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        WikiTransformationResponse response = client.transformWiki(new WikiTransformationRequest(
                "42",
                "15",
                "D1-D2",
                WikiDocumentChangeType.DOCUMENT_REPLACED,
                "# 취업 규칙\n본문...",
                "# 기존 취업 규칙\n이전 본문...",
                "# 목차\n- [휴가 규정](pages/101.md)",
                List.of(new WikiTransformationRequest.CurrentCategory("10", "인사·복무")),
                List.of(new WikiTransformationRequest.SelectedWiki(
                        "101",
                        "10",
                        "휴가 규정",
                        "연차와 반차 사용 기준",
                        "# 휴가 규정\n...",
                        List.of("15", "18"),
                        List.of("108")
                ))
        ));

        assertThat(response.summary()).isEqualTo("휴가 규정 Wiki를 생성했습니다.");
        assertThat(response.categoryChanges()).hasSize(1);
        assertThat(response.categoryChanges().get(0).action()).isEqualTo("create");
        assertThat(response.wikiChanges()).hasSize(1);
        assertThat(response.wikiChanges().get(0).evidence()).hasSize(1);
        assertThat(response.wikiChanges().get(0).evidence().get(0).quote()).isEqualTo("연차는 15일을 부여한다");
        assertThat(response.relationChanges()).isEmpty();
        assertThat(response.indexEntries()).hasSize(1);
        assertThat(response.indexEntries().get(0).wikiRef()).isEqualTo("wiki-temp-1");
        server.verify();
    }

    @Test
    @DisplayName("Wiki 변환 응답의 필수 목록이 없으면 잘못된 응답으로 처리한다")
    void mapsMissingWikiTransformationFieldsToInvalidResponse() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-transformations"))
                .andRespond(withSuccess("{\"summary\":\"ok\"}", MediaType.APPLICATION_JSON));

        AiClientException error = catchThrowableOfType(
                () -> client.transformWiki(new WikiTransformationRequest(
                        "42",
                        "15",
                        "ALL",
                        WikiDocumentChangeType.DOCUMENT_ADDED,
                        "# parsed",
                        null,
                        "# index",
                        List.of(),
                        List.of()
                )),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.INVALID_RESPONSE);
        assertThat(error.upstreamStatus()).isEqualTo(200);
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

    @Test
    @DisplayName("일정 추출 요청을 JSON 계약대로 보내고 추출된 일정을 읽는다")
    void sendsScheduleExtractionContractAndReadsPostmanSuccess() {
        server.expect(requestTo("http://localhost:8000/internal/v1/schedule-extractions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-API-Key", "local-dev-key"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(content().json("""
                        {
                          "sourceGroupKey": "schedule-source-20260727-01",
                          "parsedMarkdown": "# 8월 일정\\n...",
                          "visibilityType": "department",
                          "departmentIds": ["1", "2"]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "status": "extracted",
                          "schedules": [
                            {
                              "order": 1,
                              "title": "8월 휴가 일정",
                              "content": "개발부 휴가 일정",
                              "targetText": "개발부",
                              "location": "본사",
                              "visibilityType": "department",
                              "departmentIds": ["1", "2"],
                              "startAt": "2026-08-03T01:00:00Z",
                              "endAt": "2026-08-03T03:00:00Z"
                            }
                          ],
                          "warnings": []
                        }
                        """, MediaType.APPLICATION_JSON));

        ScheduleExtractionResponse response = client.extractSchedules(scheduleExtractionRequest());

        assertThat(response.status()).isEqualTo("extracted");
        assertThat(response.schedules()).hasSize(1);
        ScheduleExtractionResponse.ExtractedSchedule extracted = response.schedules().getFirst();
        assertThat(extracted.title()).isEqualTo("8월 휴가 일정");
        assertThat(extracted.departmentIds()).containsExactly("1", "2");
        assertThat(extracted.startAtInstant()).isEqualTo(Instant.parse("2026-08-03T01:00:00Z"));
        assertThat(extracted.endAtInstant()).isEqualTo(Instant.parse("2026-08-03T03:00:00Z"));
    }

    @Test
    @DisplayName("일정 추출 오류 응답을 계약 코드로 매핑한다")
    void mapsScheduleExtractionBadRequest() {
        server.expect(requestTo("http://localhost:8000/internal/v1/schedule-extractions"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "timestamp": "2026-07-27T09:00:00Z",
                                  "status": 400,
                                  "error": "Bad Request",
                                  "code": "INVALID_SCHEDULE_EXTRACTION_REQUEST",
                                  "message": "일정 추출 요청 구조가 올바르지 않습니다.",
                                  "path": "/internal/v1/schedule-extractions",
                                  "fieldErrors": []
                                }
                                """));

        AiClientException error = catchThrowableOfType(
                () -> client.extractSchedules(scheduleExtractionRequest()),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.BAD_REQUEST);
        assertThat(error.upstreamCode()).isEqualTo("INVALID_SCHEDULE_EXTRACTION_REQUEST");
    }

    @Test
    @DisplayName("추출 일정의 시각이 파싱 불가하면 잘못된 응답으로 처리한다")
    void mapsUnparsableExtractedInstantToInvalidResponse() {
        server.expect(requestTo("http://localhost:8000/internal/v1/schedule-extractions"))
                .andRespond(withSuccess("""
                        {
                          "status": "extracted",
                          "schedules": [
                            {
                              "order": 1,
                              "title": "8월 휴가 일정",
                              "content": null,
                              "targetText": null,
                              "location": null,
                              "visibilityType": "department",
                              "departmentIds": ["1"],
                              "startAt": "2026-08-03 01:00",
                              "endAt": "2026-08-03T03:00:00Z"
                            }
                          ],
                          "warnings": []
                        }
                        """, MediaType.APPLICATION_JSON));

        AiClientException error = catchThrowableOfType(
                () -> client.extractSchedules(scheduleExtractionRequest()),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("추출 일정의 종료 시각이 시작보다 빠르면 잘못된 응답으로 처리한다")
    void mapsInvertedExtractedPeriodToInvalidResponse() {
        server.expect(requestTo("http://localhost:8000/internal/v1/schedule-extractions"))
                .andRespond(withSuccess("""
                        {
                          "status": "extracted",
                          "schedules": [
                            {
                              "order": 1,
                              "title": "8월 휴가 일정",
                              "content": null,
                              "targetText": null,
                              "location": null,
                              "visibilityType": "department",
                              "departmentIds": ["1"],
                              "startAt": "2026-08-03T03:00:00Z",
                              "endAt": "2026-08-03T01:00:00Z"
                            }
                          ],
                          "warnings": []
                        }
                        """, MediaType.APPLICATION_JSON));

        AiClientException error = catchThrowableOfType(
                () -> client.extractSchedules(scheduleExtractionRequest()),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.INVALID_RESPONSE);
    }

    private ScheduleExtractionRequest scheduleExtractionRequest() {
        return new ScheduleExtractionRequest(
                "schedule-source-20260727-01",
                "# 8월 일정\n...",
                "department",
                List.of("1", "2")
        );
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
