package com.ajt.backend.global.ai.config;

import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.RestClientAiClient;
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
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Internal-API-Key", properties.internalApiKey())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    public AiClient aiClient(RestClient aiRestClient, ObjectMapper objectMapper) {
        return new RestClientAiClient(aiRestClient, objectMapper);
    }
}
