package com.ajt.backend.global.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
                          "wikiCapability": "capability",
                          "scopeVersion": 47
                        }
                        """))
                // 밀어 보내던 문맥이 실리지 않는다 — 에이전트가 조회 API로 직접 읽는다.
                .andExpect(content().string(not(containsString("currentIndex"))))
                .andExpect(content().string(not(containsString("currentCategories"))))
                .andExpect(content().string(not(containsString("selectedWikis"))))
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
                "capability",
                47L
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
    @DisplayName("허가값이 없으면 변환 요청을 만들 수 없다 — 창구를 못 불러 라이브를 덮는다")
    void wikiCapabilityIsRequired() {
        assertThatThrownBy(() -> new WikiTransformationRequest(
                "42", "15", "D1-D2", WikiDocumentChangeType.DOCUMENT_ADDED,
                "# 본문", null, null, 47L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("범위 버전이 없으면 변환 요청을 만들 수 없다 — 작업 중 위키가 바뀐 것을 못 잡는다")
    void scopeVersionIsRequired() {
        assertThatThrownBy(() -> new WikiTransformationRequest(
                "42", "15", "D1-D2", WikiDocumentChangeType.DOCUMENT_ADDED,
                "# 본문", null, "capability", null))
                .isInstanceOf(IllegalArgumentException.class);
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
                        "capability",
                        47L
                )),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.INVALID_RESPONSE);
        assertThat(error.upstreamStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Wiki 변환 실패 응답의 failureStage를 읽는다")
    void readsWikiTransformationFailureStage() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-transformations"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "timestamp": "2026-07-27T09:00:00Z",
                                  "status": 500,
                                  "error": "Internal Server Error",
                                  "code": "WIKI_TRANSFORMATION_FAILED",
                                  "message": "Wiki 변환에 실패했습니다.",
                                  "path": "/internal/v1/wiki-transformations",
                                  "fieldErrors": [],
                                  "failureStage": "agent_timeout"
                                }
                                """));

        AiClientException error = catchThrowableOfType(
                () -> client.transformWiki(minimalTransformationRequest()),
                AiClientException.class
        );

        assertThat(error.upstreamCode()).isEqualTo("WIKI_TRANSFORMATION_FAILED");
        assertThat(error.failureStage()).isEqualTo("agent_timeout");
    }

    @Test
    @DisplayName("failureStage가 없는 오류 응답은 실패 단계를 비워 둔다")
    void leavesFailureStageEmptyWhenAbsent() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-transformations"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "timestamp": "2026-07-27T09:00:00Z",
                                  "status": 400,
                                  "error": "Bad Request",
                                  "code": "INVALID_WIKI_TRANSFORMATION_REQUEST",
                                  "message": "Wiki 변환 요청 구조가 올바르지 않습니다.",
                                  "path": "/internal/v1/wiki-transformations",
                                  "fieldErrors": []
                                }
                                """));

        AiClientException error = catchThrowableOfType(
                () -> client.transformWiki(minimalTransformationRequest()),
                AiClientException.class
        );

        assertThat(error.failureStage()).isNull();
    }

    private WikiTransformationRequest minimalTransformationRequest() {
        return new WikiTransformationRequest(
                "42",
                "15",
                "ALL",
                WikiDocumentChangeType.DOCUMENT_ADDED,
                "# parsed",
                null,
                "capability",
                47L
        );
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

    @Test
    @DisplayName("Wiki 관리자 수정 요청을 JSON 계약대로 보내고 변경안을 읽는다")
    void sendsWikiEditContractAndReadsPostmanSuccess() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-edits"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-API-Key", "local-dev-key"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(content().json("""
                        {
                          "wikiId": "100",
                          "scopeKey": "D1-D2",
                          "instruction": "중복된 휴가 규정을 하나로 정리해줘.",
                          "wikiCapability": "capability",
                          "scopeVersion": 47,
                          "currentWiki": {
                            "title": "휴가 규정",
                            "wikiPath": "wiki/D1-D2/pages/100.md",
                            "contentMarkdown": "# 휴가 규정\\n..."
                          },
                          "evidenceDocuments": [],
                          "chatHistory": []
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "agentMessage": "중복된 휴가 규정을 하나로 정리했습니다.",
                          "wikiChanges": [
                            {
                              "action": "update",
                              "wikiId": "100",
                              "title": "휴가 규정",
                              "contentMarkdown": "# 휴가 규정\\n정리된 본문..."
                            }
                          ],
                          "categoryChanges": [],
                          "relationChanges": [],
                          "indexEntries": [
                            {
                              "wikiRef": "100",
                              "order": 1,
                              "title": "휴가 규정",
                              "summary": "정리된 휴가 규정"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        WikiEditResponse response = client.editWiki(new WikiEditRequest(
                "100",
                "D1-D2",
                "중복된 휴가 규정을 하나로 정리해줘.",
                new WikiEditRequest.WikiBody("휴가 규정", "wiki/D1-D2/pages/100.md", "# 휴가 규정\n..."),
                List.of(),
                List.of(),
                "capability",
                47L
        ));

        assertThat(response.agentMessage()).isEqualTo("중복된 휴가 규정을 하나로 정리했습니다.");
        assertThat(response.wikiChanges()).hasSize(1);
        assertThat(response.wikiChanges().getFirst().action()).isEqualTo("update");
        assertThat(response.wikiChanges().getFirst().wikiId()).isEqualTo("100");
        assertThat(response.categoryChanges()).isEmpty();
        assertThat(response.relationChanges()).isEmpty();
        assertThat(response.indexEntries()).hasSize(1);
        assertThat(response.indexEntries().getFirst().wikiRef()).isEqualTo("100");
        server.verify();
    }

    @Test
    @DisplayName("Wiki 수정 요청에 계약에 없는 필드를 보내지 않는다")
    void sendsOnlyContractFieldsForWikiEdit() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-edits"))
                .andExpect(content().string(not(containsString("currentCategories"))))
                .andRespond(withSuccess("""
                        {
                          "agentMessage": "변경이 없습니다.",
                          "wikiChanges": [],
                          "categoryChanges": [],
                          "relationChanges": [],
                          "indexEntries": []
                        }
                        """, MediaType.APPLICATION_JSON));

        client.editWiki(wikiEditRequest());

        server.verify();
    }

    @Test
    @DisplayName("Wiki 수정 요청은 근거 문서와 대화 이력을 계약대로 전달한다")
    void sendsEvidenceAndChatHistoryForWikiEdit() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-edits"))
                .andExpect(content().json("""
                        {
                          "evidenceDocuments": [
                            {
                              "documentId": "15",
                              "originalFileName": "취업규칙.pdf",
                              "parsedMarkdown": "# 취업 규칙\\n본문..."
                            }
                          ],
                          "chatHistory": [
                            {"senderType": "admin", "content": "중복을 정리해줘"},
                            {"senderType": "agent", "content": "어떤 문서를 기준으로 할까요?"}
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "agentMessage": "정리했습니다.",
                          "wikiChanges": [],
                          "categoryChanges": [],
                          "relationChanges": [],
                          "indexEntries": []
                        }
                        """, MediaType.APPLICATION_JSON));

        client.editWiki(new WikiEditRequest(
                "100",
                "D1-D2",
                "중복된 휴가 규정을 하나로 정리해줘.",
                new WikiEditRequest.WikiBody("휴가 규정", "wiki/D1-D2/pages/100.md", "# 휴가 규정\n..."),
                List.of(new WikiEditRequest.EvidenceDocument("15", "취업규칙.pdf", "# 취업 규칙\n본문...")),
                List.of(
                        new WikiEditRequest.ChatMessage("admin", "중복을 정리해줘"),
                        new WikiEditRequest.ChatMessage("agent", "어떤 문서를 기준으로 할까요?"))
        ));

        server.verify();
    }

    @Test
    @DisplayName("Wiki 수정 오류 응답을 계약 코드로 매핑한다")
    void mapsWikiEditBadRequest() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-edits"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "timestamp": "2026-07-27T09:00:00Z",
                                  "status": 400,
                                  "error": "Bad Request",
                                  "code": "INVALID_WIKI_EDIT_REQUEST",
                                  "message": "Wiki 수정 지시 또는 문맥이 올바르지 않습니다.",
                                  "path": "/internal/v1/wiki-edits",
                                  "fieldErrors": []
                                }
                                """));

        AiClientException error = catchThrowableOfType(
                () -> client.editWiki(wikiEditRequest()),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.BAD_REQUEST);
        assertThat(error.upstreamCode()).isEqualTo("INVALID_WIKI_EDIT_REQUEST");
    }

    @Test
    @DisplayName("Wiki 수정 실패 500을 계약 코드로 매핑한다")
    void mapsWikiEditFailure() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-edits"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "timestamp": "2026-07-27T09:00:00Z",
                                  "status": 500,
                                  "error": "Internal Server Error",
                                  "code": "WIKI_EDIT_FAILED",
                                  "message": "Wiki 수정에 실패했습니다.",
                                  "path": "/internal/v1/wiki-edits",
                                  "fieldErrors": []
                                }
                                """));

        AiClientException error = catchThrowableOfType(
                () -> client.editWiki(wikiEditRequest()),
                AiClientException.class
        );

        assertThat(error.upstreamStatus()).isEqualTo(500);
        assertThat(error.upstreamCode()).isEqualTo("WIKI_EDIT_FAILED");
    }

    @Test
    @DisplayName("Wiki 수정 응답에 agentMessage가 없으면 잘못된 응답으로 처리한다")
    void mapsMissingAgentMessageToInvalidResponse() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-edits"))
                .andRespond(withSuccess("""
                        {
                          "wikiChanges": [],
                          "categoryChanges": [],
                          "relationChanges": [],
                          "indexEntries": []
                        }
                        """, MediaType.APPLICATION_JSON));

        AiClientException error = catchThrowableOfType(
                () -> client.editWiki(wikiEditRequest()),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("Wiki 수정 응답의 목차 항목이 불완전하면 잘못된 응답으로 처리한다")
    void mapsIncompleteIndexEntryToInvalidResponse() {
        server.expect(requestTo("http://localhost:8000/internal/v1/wiki-edits"))
                .andRespond(withSuccess("""
                        {
                          "agentMessage": "정리했습니다.",
                          "wikiChanges": [],
                          "categoryChanges": [],
                          "relationChanges": [],
                          "indexEntries": [
                            {"wikiRef": "100", "order": null, "title": "휴가 규정", "summary": "요약"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        AiClientException error = catchThrowableOfType(
                () -> client.editWiki(wikiEditRequest()),
                AiClientException.class
        );

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("Wiki 수정 타임아웃과 연결 실패를 구분해 매핑한다")
    void mapsWikiEditTransportFailures() {
        assertThat(catchThrowableOfType(
                () -> clientWithFailure(new SocketTimeoutException()).editWiki(wikiEditRequest()),
                AiClientException.class).failureType())
                .isEqualTo(AiClientFailureType.TIMEOUT);

        assertThat(catchThrowableOfType(
                () -> clientWithFailure(new ConnectException()).editWiki(wikiEditRequest()),
                AiClientException.class).failureType())
                .isEqualTo(AiClientFailureType.CONNECTION_FAILED);
    }

    @Test
    @DisplayName("답변 요청을 계약 1.8.0 대로 보낸다 — 목차와 허가값만 싣고 본문·일정은 없다")
    void sendsAnswerContractWithIndexesOnly() {
        server.expect(requestTo("http://localhost:8000/internal/v1/answers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-API-Key", "local-dev-key"))
                .andExpect(content().json("""
                        {
                          "questionId": "500",
                          "conversationId": "chat-1",
                          "question": "연차 며칠이야?",
                          "conversationMessages": [],
                          "wikiIndexes": [
                            {
                              "scopeKey": "ALL",
                              "indexMarkdown": "# 목차",
                              "wikiCapability": "capability"
                            }
                          ]
                        }
                        """))
                // 없어진 필드가 실리지 않는다.
                .andExpect(content().string(not(containsString("selectedWikis"))))
                .andExpect(content().string(not(containsString("selectedSchedules"))))
                .andExpect(content().string(not(containsString("scheduleSummaries"))))
                .andRespond(withSuccess("""
                        {
                          "answer": "연차는 15일입니다.",
                          "sources": [
                            {"type": "wiki", "wikiId": "101", "title": "휴가 규정"}
                          ],
                          "questionType": "wiki"
                        }
                        """, MediaType.APPLICATION_JSON));

        AnswerGenerationResponse response = client.generateAnswer(answerRequest());

        assertThat(response.answer()).isEqualTo("연차는 15일입니다.");
        assertThat(response.questionType()).isEqualTo("wiki");
        assertThat(response.sources()).singleElement()
                .satisfies(source -> {
                    assertThat(source.wikiId()).isEqualTo("101");
                    assertThat(source.title()).isEqualTo("휴가 규정");
                });
        server.verify();
    }

    @Test
    @DisplayName("근거를 못 찾은 답변(빈 sources)은 정상 응답으로 읽는다")
    void readsAnswerWithoutSourcesAsSuccess() {
        server.expect(requestTo("http://localhost:8000/internal/v1/answers"))
                .andRespond(withSuccess("""
                        {"answer": "정보가 부족합니다.", "sources": [], "questionType": "wiki"}
                        """, MediaType.APPLICATION_JSON));

        AnswerGenerationResponse response = client.generateAnswer(answerRequest());

        assertThat(response.sources()).isEmpty();
        assertThat(response.answer()).isEqualTo("정보가 부족합니다.");
    }

    @Test
    @DisplayName("questionType이 없는 응답은 잘못된 응답으로 본다 — question 테이블에 저장할 값이다")
    void rejectsAnswerWithoutQuestionType() {
        server.expect(requestTo("http://localhost:8000/internal/v1/answers"))
                .andRespond(withSuccess("""
                        {"answer": "답", "sources": []}
                        """, MediaType.APPLICATION_JSON));

        AiClientException error = catchThrowableOfType(
                AiClientException.class, () -> client.generateAnswer(answerRequest()));

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("출처에 title이 없으면 잘못된 응답으로 본다 — source_title이 NOT NULL이다")
    void rejectsSourceWithoutTitle() {
        server.expect(requestTo("http://localhost:8000/internal/v1/answers"))
                .andRespond(withSuccess("""
                        {
                          "answer": "답",
                          "sources": [{"type": "wiki", "wikiId": "101"}],
                          "questionType": "wiki"
                        }
                        """, MediaType.APPLICATION_JSON));

        AiClientException error = catchThrowableOfType(
                AiClientException.class, () -> client.generateAnswer(answerRequest()));

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("계약이 정한 오류 이름을 그대로 실어 올린다 — 재시도 판단 근거다")
    void keepsContractErrorCodeFromAnswerFailure() {
        server.expect(requestTo("http://localhost:8000/internal/v1/answers"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"status": 500, "code": "AGENT_TIMED_OUT",
                                 "message": "시간 상한에 걸렸습니다.", "fieldErrors": []}
                                """));

        AiClientException error = catchThrowableOfType(
                AiClientException.class, () -> client.generateAnswer(answerRequest()));

        assertThat(error.failureType()).isEqualTo(AiClientFailureType.SERVER_ERROR);
        assertThat(error.upstreamCode()).isEqualTo("AGENT_TIMED_OUT");
    }

    private AnswerGenerationRequest answerRequest() {
        return new AnswerGenerationRequest(
                "500",
                "chat-1",
                "연차 며칠이야?",
                List.of(),
                List.of(new AnswerGenerationRequest.WikiIndex("ALL", "# 목차", "capability"))
        );
    }

    private WikiEditRequest wikiEditRequest() {
        return new WikiEditRequest(
                "100",
                "D1-D2",
                "중복된 휴가 규정을 하나로 정리해줘.",
                new WikiEditRequest.WikiBody("휴가 규정", "wiki/D1-D2/pages/100.md", "# 휴가 규정\n..."),
                List.of(),
                List.of()
        );
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
