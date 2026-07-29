package com.ajt.backend.global.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@DisplayName("FastAPI 내부 호출 설정")
class AiApiConfigTest {

    @Configuration
    static class ObjectMapperConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ObjectMapperConfig.class, AiApiConfig.class);

    @Test
    @DisplayName("내부 API 키가 비어 있으면 기동에 실패한다")
    void failsToStartWhenInternalApiKeyIsBlank() {
        runner.withPropertyValues(
                        "ajt.ai.base-url=http://ai.example:8100",
                        "ajt.ai.internal-api-key=",
                        "ajt.ai.connect-timeout=5s",
                        "ajt.ai.read-timeout=10m")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ajt.ai.internal-api-key"));
    }

    @Test
    @DisplayName("base-url이 비어 있으면 기동에 실패한다")
    void failsToStartWhenBaseUrlIsBlank() {
        runner.withPropertyValues(
                        "ajt.ai.base-url=",
                        "ajt.ai.internal-api-key=key",
                        "ajt.ai.connect-timeout=5s",
                        "ajt.ai.read-timeout=10m")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ajt.ai.base-url"));
    }

    @Test
    @DisplayName("키가 있으면 공용 클라이언트와 일정 추출 클라이언트를 각각 만든다")
    void createsSeparateClientsForScheduleExtraction() {
        runner.withPropertyValues(
                        "ajt.ai.base-url=http://ai.example:8100",
                        "ajt.ai.internal-api-key=test-key",
                        "ajt.ai.connect-timeout=5s",
                        "ajt.ai.read-timeout=10m",
                        "ajt.ai.schedule-extraction-read-timeout=180s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("aiRestClient");
                    assertThat(context).hasBean("scheduleExtractionRestClient");
                    assertThat(context.getBean("aiRestClient"))
                            .isNotSameAs(context.getBean("scheduleExtractionRestClient"));
                    assertThat(context.getBean(AiApiProperties.class).scheduleExtractionReadTimeout())
                            .isEqualTo(Duration.ofSeconds(180));
                });
    }

    @Test
    @DisplayName("일정 추출 타임아웃을 지정하지 않으면 계약 상한인 180초를 쓴다")
    void defaultsScheduleExtractionTimeoutToContractLimit() {
        runner.withPropertyValues(
                        "ajt.ai.base-url=http://ai.example:8100",
                        "ajt.ai.internal-api-key=test-key",
                        "ajt.ai.connect-timeout=5s",
                        "ajt.ai.read-timeout=10m")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AiApiProperties.class).scheduleExtractionReadTimeout())
                            .isEqualTo(Duration.ofSeconds(180));
                });
    }
}
