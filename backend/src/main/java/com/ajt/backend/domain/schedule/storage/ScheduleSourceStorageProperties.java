package com.ajt.backend.domain.schedule.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ajt.schedule.source-storage")
public record ScheduleSourceStorageProperties(Path rootPath) {
}
