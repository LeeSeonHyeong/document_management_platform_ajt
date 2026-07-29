package com.ajt.backend.domain.inquiry.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(InquiryStorageProperties.class)
public class InquiryStorageConfig {

    @Bean
    InquiryFileStorage inquiryFileStorage(InquiryStorageProperties properties) {
        return new LocalInquiryFileStorage(properties.rootPath());
    }
}
