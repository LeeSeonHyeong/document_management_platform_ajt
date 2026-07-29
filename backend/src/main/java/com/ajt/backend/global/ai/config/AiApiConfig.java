package com.ajt.backend.global.ai.config;

import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.RestClientAiClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(AiApiProperties.class)
public class AiApiConfig {

    @Bean
    public RestClient aiRestClient(AiApiProperties properties) {
        return aiRestClient(properties, properties.readTimeout());
    }

    /**
     * 일정 추출 전용 클라이언트입니다.
     * 일정 원본문서 업로드는 계약상 최대 180초 동기 처리인데, 파싱·Wiki 변환은 더 오래 걸려
     * 공용 read-timeout을 줄일 수 없다. 그래서 이 호출만 짧은 타임아웃을 쓴다.
     */
    @Bean
    public RestClient scheduleExtractionRestClient(AiApiProperties properties) {
        return aiRestClient(properties, properties.scheduleExtractionReadTimeout());
    }

    @Bean
    public AiClient aiClient(
            RestClient aiRestClient,
            RestClient scheduleExtractionRestClient,
            ObjectMapper objectMapper
    ) {
        return new RestClientAiClient(aiRestClient, scheduleExtractionRestClient, objectMapper);
    }

    private RestClient aiRestClient(AiApiProperties properties, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Internal-API-Key", properties.internalApiKey())
                .requestFactory(requestFactory)
                .build();
    }
}
