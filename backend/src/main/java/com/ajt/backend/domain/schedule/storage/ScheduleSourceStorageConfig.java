package com.ajt.backend.domain.schedule.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ScheduleSourceStorageProperties.class)
public class ScheduleSourceStorageConfig {

    @Bean
    ScheduleSourceFileStorage scheduleSourceFileStorage(ScheduleSourceStorageProperties properties) {
        return new LocalScheduleSourceFileStorage(properties.rootPath());
    }
}
