package com.ajt.backend.domain.inquiry.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ajt.inquiry.storage")
public record InquiryStorageProperties(Path rootPath) {
}
