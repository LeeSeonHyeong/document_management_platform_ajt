package com.ajt.backend.global.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ajt.ai")
public record AiApiProperties(
        String baseUrl,
        String internalApiKey,
        Duration connectTimeout,
        Duration readTimeout
) {
}
