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
