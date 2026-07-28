package com.ajt.backend.domain.document.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DocumentStorageProperties.class)
public class DocumentStorageConfig {

    @Bean
    DocumentFileStorage documentFileStorage(DocumentStorageProperties properties) {
        return new LocalDocumentFileStorage(properties.rootPath());
    }
}
