# FastAPI Source Parse Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Spring Boot client that sends an original Wiki or schedule document to FastAPI's `POST /internal/v1/source-parses` endpoint and returns the contract response.

**Architecture:** Keep FastAPI transport code inside `global.ai`: a typed `ajt.ai` configuration creates one authenticated `RestClient`, while `RestClientAiClient` implements the application-facing `AiClient` interface. Request/response DTOs describe the fixed Postman contract; client failures remain `AiClientException` so later Wiki and schedule workflows decide their own public API behavior.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring MVC `RestClient`, `MockRestServiceServer`, JUnit 5, MySQL-compatible backend configuration.

## Global Constraints

- Requirements definition version is v2.5 and the Postman development contract is v1.0.0.
- The DB platform is MySQL 8.4 LTS; this client must not add tables or change the 16-table schema.
- Only Spring Boot calls FastAPI; the Frontend must never call FastAPI directly.
- Call exactly `POST /internal/v1/source-parses` with header `X-Internal-API-Key` and multipart parts `requestId`, `sourceType`, `sourceId`, `file`, `originalFileName`, and `mimeType`.
- `sourceType` wire values are exactly `wiki` and `schedule`; all internal DB identifiers are strings.
- Do not change generated Postman JSON directly. Contract changes require editing the generator and examples first, but this plan does not change the contract.
- FastAPI must not receive DB connection information, storage paths, or the internal key in response/log messages; it does not write AJT DB or service files.
- Public `BusinessException` and public error handlers are not changed: this client exposes a transport-level `AiClientException` to its caller.
- No reactive dependency, controller, frontend, DB persistence, or other five internal FastAPI APIs are in scope.

---

## File Structure

- Create `backend/src/main/java/com/ajt/backend/global/ai/config/AiApiProperties.java` — binds `ajt.ai` endpoint, key, and timeouts.
- Create `backend/src/main/java/com/ajt/backend/global/ai/config/AiApiConfig.java` — creates the timeout-aware, authenticated `RestClient`.
- Create `backend/src/main/java/com/ajt/backend/global/ai/client/AiClient.java` — application boundary for internal AI calls.
- Create `backend/src/main/java/com/ajt/backend/global/ai/client/RestClientAiClient.java` — multipart `source-parses` HTTP implementation.
- Create `backend/src/main/java/com/ajt/backend/global/ai/client/SourceType.java` — enum with JSON wire values `wiki` and `schedule`.
- Create `backend/src/main/java/com/ajt/backend/global/ai/client/SourceParseRequest.java` — validated outgoing source-parse input.
- Create `backend/src/main/java/com/ajt/backend/global/ai/client/SourceParseResponse.java` — FastAPI success response DTO.
- Create `backend/src/main/java/com/ajt/backend/global/ai/client/AiApiErrorResponse.java` — FastAPI error-body DTO.
- Create `backend/src/main/java/com/ajt/backend/global/ai/client/AiClientFailureType.java` — stable client failure classification.
- Create `backend/src/main/java/com/ajt/backend/global/ai/client/AiClientException.java` — preserves upstream metadata without exposing secrets or file contents.
- Create `backend/src/main/java/com/ajt/backend/global/ai/client/AiClientErrorMapper.java` — maps FastAPI error status/body into `AiClientException` independently of HTTP transport.
- Create `backend/src/test/java/com/ajt/backend/global/ai/config/AiApiPropertiesTest.java` — configuration binding/default tests.
- Create `backend/src/test/java/com/ajt/backend/global/ai/client/RestClientAiClientTest.java` — real `RestClient` plus mock-server transport contract tests.
- Create `backend/src/test/java/com/ajt/backend/global/ai/client/AiClientErrorMapperTest.java` — error status/body mapping tests.
- Modify `backend/src/main/resources/application.yml` — add only the `ajt.ai` defaults and environment overrides.

### Task 1: Typed AI Configuration and Contract Models

**Files:**
- Create: `backend/src/main/java/com/ajt/backend/global/ai/config/AiApiProperties.java`
- Create: `backend/src/main/java/com/ajt/backend/global/ai/config/AiApiConfig.java`
- Create: `backend/src/main/java/com/ajt/backend/global/ai/client/AiClient.java`
- Create: `backend/src/main/java/com/ajt/backend/global/ai/client/SourceType.java`
- Create: `backend/src/main/java/com/ajt/backend/global/ai/client/SourceParseRequest.java`
- Create: `backend/src/main/java/com/ajt/backend/global/ai/client/SourceParseResponse.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/ajt/backend/global/ai/config/AiApiPropertiesTest.java`

**Interfaces:**
- Consumes: Spring Boot configuration binding and `org.springframework.core.io.Resource`.
- Produces: `AiClient.parseSource(SourceParseRequest request): SourceParseResponse`; `SourceType.WIKI` serializes as `wiki`, `SourceType.SCHEDULE` serializes as `schedule`; `AiApiProperties` exposes `baseUrl`, `internalApiKey`, `connectTimeout`, and `readTimeout`.

- [ ] **Step 1: Write the failing configuration/model tests**

```java
@SpringBootTest(properties = {
    "ajt.ai.base-url=http://ai.example:8100",
    "ajt.ai.internal-api-key=test-key",
    "ajt.ai.connect-timeout=7s",
    "ajt.ai.read-timeout=181s"
})
class AiApiPropertiesTest {
    @Autowired AiApiProperties properties;

    @Test void bindsAiProperties() {
        assertThat(properties.baseUrl()).isEqualTo("http://ai.example:8100");
        assertThat(properties.internalApiKey()).isEqualTo("test-key");
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(181));
    }

    @Test void sourceTypeUsesContractWireValues() {
        assertThat(SourceType.WIKI.value()).isEqualTo("wiki");
        assertThat(SourceType.SCHEDULE.value()).isEqualTo("schedule");
    }
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run: `cd backend && bash gradlew test --tests '*AiApiPropertiesTest'`

Expected: FAIL because the AI configuration and model types do not exist.

- [ ] **Step 3: Implement configuration and immutable DTOs**

```java
@ConfigurationProperties(prefix = "ajt.ai")
public record AiApiProperties(
    String baseUrl, String internalApiKey,
    Duration connectTimeout, Duration readTimeout
) {}

public enum SourceType {
    WIKI("wiki"), SCHEDULE("schedule");
    private final String value;
    SourceType(String value) { this.value = value; }
    @JsonValue public String value() { return value; }
}

public record SourceParseRequest(
    String requestId, SourceType sourceType, String sourceId,
    Resource file, String originalFileName, String mimeType
) {
    public SourceParseRequest {
        requireNonNull(requestId); requireNonNull(sourceType); requireNonNull(sourceId);
        requireNonNull(file); requireNonNull(originalFileName); requireNonNull(mimeType);
    }
}
```

Add `@EnableConfigurationProperties(AiApiProperties.class)` to `AiApiConfig` and add this exact configuration to `application.yml`:

```yaml
ajt:
  ai:
    base-url: ${AI_BASE_URL:http://localhost:8000}
    internal-api-key: ${AI_INTERNAL_API_KEY:local-dev-key}
    connect-timeout: ${AI_CONNECT_TIMEOUT:5s}
    read-timeout: ${AI_READ_TIMEOUT:180s}
```

Define `SourceParseResponse(String requestId, SourceType sourceType, String sourceId, String parsedMarkdown, List<String> warnings)` and `AiClient` with `SourceParseResponse parseSource(SourceParseRequest request);`. Do not add endpoint behavior in this task.

- [ ] **Step 4: Run the focused tests and verify they pass**

Run: `cd backend && bash gradlew test --tests '*AiApiPropertiesTest'`

Expected: PASS; values bind from Spring properties and source type values match the contract.

- [ ] **Step 5: Commit the self-contained configuration/model change**

```bash
git add backend/src/main/java/com/ajt/backend/global/ai/config/AiApiProperties.java backend/src/main/java/com/ajt/backend/global/ai/config/AiApiConfig.java backend/src/main/java/com/ajt/backend/global/ai/client/AiClient.java backend/src/main/java/com/ajt/backend/global/ai/client/SourceType.java backend/src/main/java/com/ajt/backend/global/ai/client/SourceParseRequest.java backend/src/main/java/com/ajt/backend/global/ai/client/SourceParseResponse.java backend/src/main/resources/application.yml backend/src/test/java/com/ajt/backend/global/ai/config/AiApiPropertiesTest.java
git commit -m "feat(ai): FastAPI 클라이언트 설정과 계약 모델 추가"
```

### Task 2: AI Failure Model and Response Mapping

**Files:**
- Create: `backend/src/main/java/com/ajt/backend/global/ai/client/AiApiErrorResponse.java`
- Create: `backend/src/main/java/com/ajt/backend/global/ai/client/AiClientFailureType.java`
- Create: `backend/src/main/java/com/ajt/backend/global/ai/client/AiClientException.java`
- Create: `backend/src/main/java/com/ajt/backend/global/ai/client/AiClientErrorMapper.java`
- Test: `backend/src/test/java/com/ajt/backend/global/ai/client/AiClientErrorMapperTest.java`

**Interfaces:**
- Consumes: `SourceParseResponse` and FastAPI error JSON fields `status`, `code`, `message`, `fieldErrors`.
- Produces: `AiClientException(AiClientFailureType failureType, Integer upstreamStatus, String upstreamCode, String upstreamMessage, List<FieldErrorResponse> fieldErrors, Throwable cause)`; `FieldErrorResponse` is the existing `com.ajt.backend.global.error.FieldErrorResponse`; classification values are `BAD_REQUEST`, `UNAUTHORIZED`, `SERVER_ERROR`, `UNEXPECTED_STATUS`, `CONNECTION_FAILED`, `TIMEOUT`, and `INVALID_RESPONSE`.

- [ ] **Step 1: Write failing error-mapping tests**

```java
@Test void mapsBadRequestErrorBody() {
    server.expect(requestTo("http://localhost:8000/internal/v1/source-parses"))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST)
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"status\":400,\"code\":\"INVALID_SOURCE_PARSE_REQUEST\",\"message\":\"필수 값이 누락되었습니다.\",\"fieldErrors\":[]}"));

    AiClientException error = catchThrowableOfType(() -> client.parseSource(request()), AiClientException.class);
    assertThat(error.failureType()).isEqualTo(AiClientFailureType.BAD_REQUEST);
    assertThat(error.upstreamStatus()).isEqualTo(400);
    assertThat(error.upstreamCode()).isEqualTo("INVALID_SOURCE_PARSE_REQUEST");
}
```

Add analogous tests that expect `UNAUTHORIZED` for 401, `SERVER_ERROR` for 500, and `UNEXPECTED_STATUS` for 404.

- [ ] **Step 2: Run the error tests and verify they fail**

Run: `cd backend && bash gradlew test --tests '*RestClientAiClientTest'`

Expected: FAIL because the exception and error-body types do not exist.

- [ ] **Step 3: Implement failure metadata without public-error coupling**

```java
public enum AiClientFailureType {
    BAD_REQUEST, UNAUTHORIZED, SERVER_ERROR, UNEXPECTED_STATUS,
    CONNECTION_FAILED, TIMEOUT, INVALID_RESPONSE
}

public class AiClientException extends RuntimeException {
    // Store failure type, nullable upstream status/code/message, field errors, and cause.
    // Its message must identify only failure type and status; never concatenate key, file data, or path.
}
```

Define `AiApiErrorResponse(Integer status, String error, String code, String message, String path, List<FieldErrorResponse> fieldErrors)` and use a private `RestClientAiClient.toAiClientException(HttpStatusCode, String)` method to deserialize it defensively. Map 400, 401, 500-or-greater, and all other non-2xx statuses exactly to the four tested classifications. If FastAPI sends malformed error JSON, retain the status and use no upstream code/message rather than throwing a JSON parsing exception.

- [ ] **Step 4: Run the focused tests and verify they pass**

Run: `cd backend && bash gradlew test --tests '*RestClientAiClientTest'`

Expected: PASS; every tested HTTP status becomes the specified `AiClientFailureType` and keeps safe upstream metadata.

- [ ] **Step 5: Commit the error model**

```bash
git add backend/src/main/java/com/ajt/backend/global/ai/client/AiApiErrorResponse.java backend/src/main/java/com/ajt/backend/global/ai/client/AiClientFailureType.java backend/src/main/java/com/ajt/backend/global/ai/client/AiClientException.java backend/src/test/java/com/ajt/backend/global/ai/client/RestClientAiClientTest.java
git commit -m "feat(ai): FastAPI 오류 응답 변환 추가"
```

### Task 3: Multipart Source-Parse RestClient Implementation

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/global/ai/config/AiApiConfig.java`
- Create: `backend/src/main/java/com/ajt/backend/global/ai/client/RestClientAiClient.java`
- Modify: `backend/src/test/java/com/ajt/backend/global/ai/client/RestClientAiClientTest.java`

**Interfaces:**
- Consumes: `AiApiProperties`, `AiClient`, `SourceParseRequest`, `SourceParseResponse`, `AiClientException`, and `AiClientFailureType`.
- Produces: a Spring bean implementing `AiClient`; `parseSource` posts the exact six multipart fields to `/internal/v1/source-parses` and returns a validated `SourceParseResponse`.

- [ ] **Step 1: Write a failing success-contract test with a real RestClient**

```java
@Test void sendsExactMultipartContractAndReadsPostmanSuccess() {
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
        .andRespond(withSuccess("{\"requestId\":\"req-001\",\"sourceType\":\"schedule\",\"sourceId\":\"schedule-source-001\",\"parsedMarkdown\":\"# 회의록\",\"warnings\":[]}", MediaType.APPLICATION_JSON));

    SourceParseResponse response = client.parseSource(request());
    assertThat(response.parsedMarkdown()).isEqualTo("# 회의록");
    assertThat(response.warnings()).isEmpty();
}
```

- [ ] **Step 2: Run the success test and verify it fails**

Run: `cd backend && bash gradlew test --tests '*RestClientAiClientTest.sendsExactMultipartContractAndReadsPostmanSuccess'`

Expected: FAIL because `RestClientAiClient` has not been implemented.

- [ ] **Step 3: Implement the authenticated, timeout-aware multipart call**

```java
MultipartBodyBuilder body = new MultipartBodyBuilder();
body.part("requestId", request.requestId());
body.part("sourceType", request.sourceType().value());
body.part("sourceId", request.sourceId());
body.part("file", request.file())
    .filename(request.originalFileName())
    .contentType(MediaType.parseMediaType(request.mimeType()));
body.part("originalFileName", request.originalFileName());
body.part("mimeType", request.mimeType());

return restClient.post().uri("/internal/v1/source-parses")
    .contentType(MediaType.MULTIPART_FORM_DATA)
    .body(body.build())
    .retrieve()
    .onStatus(HttpStatusCode::isError, this::throwMappedHttpError)
    .body(SourceParseResponse.class);
```

Build `RestClient` with base URL, default `X-Internal-API-Key`, a `SimpleClientHttpRequestFactory` whose connect/read timeouts come from `AiApiProperties`, and an `ObjectMapper`-backed message converter from the normal Spring Boot MVC stack. Reject a null body, blank `requestId`/`sourceId`/`parsedMarkdown`, null `sourceType`, or null `warnings` as `INVALID_RESPONSE`. Catch `ResourceAccessException`; inspect its cause chain for `SocketTimeoutException` and classify it as `TIMEOUT`, otherwise classify it as `CONNECTION_FAILED`.

- [ ] **Step 4: Add and run invalid-response and transport-failure tests**

```java
@Test void mapsMissingSuccessFieldsToInvalidResponse() {
    server.expect(anything()).andRespond(withSuccess("{\"requestId\":\"req-001\"}", MediaType.APPLICATION_JSON));
    assertThat(catchThrowableOfType(() -> client.parseSource(request()), AiClientException.class).failureType())
        .isEqualTo(AiClientFailureType.INVALID_RESPONSE);
}

@Test void mapsSocketTimeoutToTimeout() {
    RestClientAiClient timeoutClient = clientWithRequestFactory(request -> {
        throw new ResourceAccessException("read timed out", new SocketTimeoutException());
    });
    AiClientException error = catchThrowableOfType(() -> timeoutClient.parseSource(request()), AiClientException.class);
    assertThat(error.failureType()).isEqualTo(AiClientFailureType.TIMEOUT);
}
```

In the test class, implement `clientWithRequestFactory(ClientHttpRequestFactory factory)` by constructing the same `RestClient` configuration with the supplied factory and returning `new RestClientAiClient(restClient, objectMapper)`. Add a second test with `new ConnectException()` and assert `CONNECTION_FAILED`.

Run: `cd backend && bash gradlew test --tests '*RestClientAiClientTest'`

Expected: PASS; success, non-2xx, malformed-success, connection failure, and timeout cases are all classified as specified.

- [ ] **Step 5: Commit the endpoint implementation and tests**

```bash
git add backend/src/main/java/com/ajt/backend/global/ai/config/AiApiConfig.java backend/src/main/java/com/ajt/backend/global/ai/client/RestClientAiClient.java backend/src/test/java/com/ajt/backend/global/ai/client/RestClientAiClientTest.java
git commit -m "feat(ai): 원본문서 파싱 FastAPI 연동 추가"
```

### Task 4: Contract Evidence and Full Regression Verification

**Files:**
- Modify: `backend/docs/superpowers/specs/2026-07-27-fastapi-source-parse-client-design.md` only if implementation reveals a factual mismatch; otherwise no contract document changes.
- Test: all backend tests and artifact-consistency validation.

**Interfaces:**
- Consumes: completed `AiClient` implementation and the existing Postman v1.0.0 contract.
- Produces: verified evidence that source parsing preserves the agreed API contract without generated-collection changes.

- [ ] **Step 1: Run targeted tests as an implementation gate**

```bash
cd backend && bash gradlew test --tests '*AiApiPropertiesTest' --tests '*RestClientAiClientTest'
```

Expected: PASS with tests covering URL, header, six multipart parts, supplied filename/MIME type, 200 response mapping, error statuses, malformed response, connection failure, and timeout.

- [ ] **Step 2: Run full backend regression tests**

```bash
cd backend && bash gradlew test
```

Expected: `BUILD SUCCESSFUL` with existing global error and health tests still passing.

- [ ] **Step 3: Run artifact consistency validation without modifying generated JSON**

```bash
node scripts/validate-artifact-consistency.mjs
```

Expected: reports `16 tables, 53 public APIs, 6 internal APIs` and exits successfully.

- [ ] **Step 4: Inspect the final diff for secrets and contract drift**

```bash
git diff origin/develop -- backend/src/main/java/com/ajt/backend/global/ai backend/src/main/resources/application.yml backend/src/test/java/com/ajt/backend/global/ai
git status --short
```

Expected: no actual API key, file content, storage path, generated Postman JSON, schema change, controller change, or unrelated untracked user file is staged.

- [ ] **Step 5: Commit only a factual design correction if one was required**

```bash
git add backend/docs/superpowers/specs/2026-07-27-fastapi-source-parse-client-design.md
git commit -m "docs(ai): 원본문서 파싱 구현 결과 반영"
```

Skip this commit when no factual design correction was necessary; do not make a no-op documentation commit.
