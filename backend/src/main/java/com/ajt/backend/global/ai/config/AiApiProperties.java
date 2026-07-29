package com.ajt.backend.global.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FastAPI 내부 호출 설정입니다.
 * 내부 API 키가 비어 있으면 기동을 실패시킨다. 기본값을 두면 운영에서 환경변수를 빠뜨렸을 때
 * 알려진 키로 내부 API가 열리기 때문이다. 로컬 기본값은 application-local.yml에만 둔다.
 */
@ConfigurationProperties(prefix = "ajt.ai")
public record AiApiProperties(
        String baseUrl,
        String internalApiKey,
        Duration connectTimeout,
        Duration readTimeout,
        Duration scheduleExtractionReadTimeout
) {
    private static final Duration DEFAULT_SCHEDULE_EXTRACTION_READ_TIMEOUT = Duration.ofSeconds(180);

    public AiApiProperties {
        baseUrl = requireNotBlank(baseUrl, "ajt.ai.base-url");
        internalApiKey = requireNotBlank(internalApiKey, "ajt.ai.internal-api-key");
        // 계약상 일정 추출은 최대 180초 동기 처리다. 값을 주지 않으면 계약 상한을 그대로 쓴다.
        scheduleExtractionReadTimeout = scheduleExtractionReadTimeout == null
                ? DEFAULT_SCHEDULE_EXTRACTION_READ_TIMEOUT
                : scheduleExtractionReadTimeout;
    }

    private static String requireNotBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    propertyName + "이(가) 비어 있습니다. 운영 환경에서는 환경변수로 반드시 지정해야 합니다.");
        }
        return value;
    }
}
