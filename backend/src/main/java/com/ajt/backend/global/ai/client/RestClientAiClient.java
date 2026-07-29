package com.ajt.backend.global.ai.client;

import com.ajt.backend.global.error.FieldErrorResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

public class RestClientAiClient implements AiClient {

    private static final String SOURCE_PARSE_PATH = "/internal/v1/source-parses";
    private static final String SCHEDULE_EXTRACTION_PATH = "/internal/v1/schedule-extractions";
    private static final String WIKI_CONTEXT_SELECTION_PATH = "/internal/v1/wiki-context-selections";
    private static final String WIKI_TRANSFORMATION_PATH = "/internal/v1/wiki-transformations";

    private final RestClient restClient;
    private final AiClientErrorMapper errorMapper;

    public RestClientAiClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.errorMapper = new AiClientErrorMapper(objectMapper);
    }

    @Override
    public SourceParseResponse parseSource(SourceParseRequest request) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("requestId", request.requestId());
        body.add("sourceType", request.sourceType().value());
        body.add("sourceId", request.sourceId());
        body.add("file", filePart(request));
        body.add("originalFileName", request.originalFileName());
        body.add("mimeType", request.mimeType());

        try {
            SourceParseResponse response = restClient.post()
                    .uri(SOURCE_PARSE_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::throwMappedHttpError)
                    .body(SourceParseResponse.class);

            return validateResponse(response);
        } catch (ResourceAccessException exception) {
            throw transportFailure(exception);
        }
    }

    @Override
    public ScheduleExtractionResponse extractSchedules(ScheduleExtractionRequest request) {
        try {
            ScheduleExtractionResponse response = restClient.post()
                    .uri(SCHEDULE_EXTRACTION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::throwMappedHttpError)
                    .body(ScheduleExtractionResponse.class);

            return validateResponse(response);
        } catch (ResourceAccessException exception) {
            throw transportFailure(exception);
        }
    }

    @Override
    public WikiContextSelectionResponse selectWikiContext(WikiContextSelectionRequest request) {
        try {
            WikiContextSelectionResponse response = restClient.post()
                    .uri(WIKI_CONTEXT_SELECTION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::throwMappedHttpError)
                    .body(WikiContextSelectionResponse.class);

            return validateResponse(response);
        } catch (ResourceAccessException exception) {
            throw transportFailure(exception);
        }
    }

    @Override
    public WikiTransformationResponse transformWiki(WikiTransformationRequest request) {
        try {
            WikiTransformationResponse response = restClient.post()
                    .uri(WIKI_TRANSFORMATION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::throwMappedHttpError)
                    .body(WikiTransformationResponse.class);

            return validateResponse(response);
        } catch (ResourceAccessException exception) {
            throw transportFailure(exception);
        }
    }

    private HttpEntity<?> filePart(SourceParseRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(request.mimeType()));
        headers.setContentDispositionFormData("file", request.originalFileName());
        return new HttpEntity<>(request.file(), headers);
    }

    private void throwMappedHttpError(org.springframework.http.HttpRequest request, ClientHttpResponse response)
            throws IOException {
        String responseBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        throw errorMapper.map(response.getStatusCode(), responseBody);
    }

    private SourceParseResponse validateResponse(SourceParseResponse response) {
        if (response == null
                || isBlank(response.requestId())
                || response.sourceType() == null
                || isBlank(response.sourceId())
                || isBlank(response.parsedMarkdown())
                || response.warnings() == null) {
            throw new AiClientException(
                    AiClientFailureType.INVALID_RESPONSE,
                    200,
                    null,
                    null,
                    List.<FieldErrorResponse>of(),
                    null
            );
        }
        return response;
    }

    private ScheduleExtractionResponse validateResponse(ScheduleExtractionResponse response) {
        if (response == null
                || isBlank(response.status())
                || response.schedules() == null
                || response.warnings() == null
                || response.schedules().stream().anyMatch(this::isInvalid)) {
            throw invalidResponse();
        }
        return new ScheduleExtractionResponse(
                response.status(),
                List.copyOf(response.schedules()),
                List.copyOf(response.warnings())
        );
    }

    /**
     * 추출된 일정 하나가 일정으로 저장할 수 있는 형태인지 확인합니다.
     * 시각은 파싱 가능한지, 기간이 뒤집히지 않았는지까지 여기서 걸러 서비스로 넘긴다.
     */
    private boolean isInvalid(ScheduleExtractionResponse.ExtractedSchedule schedule) {
        if (schedule == null
                || schedule.order() == null
                || isBlank(schedule.title())
                || isBlank(schedule.visibilityType())
                || schedule.departmentIds() == null
                || isBlank(schedule.startAt())
                || isBlank(schedule.endAt())) {
            return true;
        }
        try {
            return schedule.endAtInstant().isBefore(schedule.startAtInstant());
        } catch (java.time.format.DateTimeParseException exception) {
            return true;
        }
    }

    private AiClientException invalidResponse() {
        return new AiClientException(
                AiClientFailureType.INVALID_RESPONSE,
                200,
                null,
                null,
                List.<FieldErrorResponse>of(),
                null
        );
    }

    private WikiContextSelectionResponse validateResponse(WikiContextSelectionResponse response) {
        if (response == null
                || response.wikiIds() == null
                || response.wikiIds().size() > 5
                || response.wikiIds().stream().anyMatch(this::isBlank)
                || response.reason() == null) {
            throw new AiClientException(
                    AiClientFailureType.INVALID_RESPONSE,
                    200,
                    null,
                    null,
                    List.<FieldErrorResponse>of(),
                    null
            );
        }
        return new WikiContextSelectionResponse(List.copyOf(response.wikiIds()), response.reason());
    }

    private WikiTransformationResponse validateResponse(WikiTransformationResponse response) {
        if (response == null
                || isBlank(response.summary())
                || response.categoryChanges() == null
                || response.wikiChanges() == null
                || response.relationChanges() == null
                || response.indexEntries() == null
                || response.categoryChanges().stream().anyMatch(change -> change == null || isBlank(change.action()))
                || response.wikiChanges().stream().anyMatch(change -> change == null || isBlank(change.action()))
                || response.relationChanges().stream().anyMatch(change -> change == null || isBlank(change.action()))
                || response.indexEntries().stream().anyMatch(entry -> entry == null
                || isBlank(entry.wikiRef())
                || entry.order() == null
                || isBlank(entry.title())
                || isBlank(entry.summary()))) {
            throw new AiClientException(
                    AiClientFailureType.INVALID_RESPONSE,
                    200,
                    null,
                    null,
                    List.<FieldErrorResponse>of(),
                    null
            );
        }
        return new WikiTransformationResponse(
                response.summary(),
                List.copyOf(response.categoryChanges()),
                List.copyOf(response.wikiChanges()),
                List.copyOf(response.relationChanges()),
                List.copyOf(response.indexEntries())
        );
    }

    private AiClientException transportFailure(ResourceAccessException exception) {
        AiClientFailureType failureType = hasCause(exception, SocketTimeoutException.class)
                ? AiClientFailureType.TIMEOUT
                : AiClientFailureType.CONNECTION_FAILED;

        return new AiClientException(
                failureType,
                null,
                null,
                null,
                List.<FieldErrorResponse>of(),
                exception
        );
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
