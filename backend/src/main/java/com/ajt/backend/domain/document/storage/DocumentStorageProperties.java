package com.ajt.backend.domain.document.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ajt.document.storage")
public record DocumentStorageProperties(Path rootPath) {
}
