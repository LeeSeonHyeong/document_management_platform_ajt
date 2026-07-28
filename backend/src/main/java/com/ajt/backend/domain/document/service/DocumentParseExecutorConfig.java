package com.ajt.backend.domain.document.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocumentParseExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    ExecutorService documentParseExecutor() {
        return Executors.newSingleThreadExecutor();
    }
}
