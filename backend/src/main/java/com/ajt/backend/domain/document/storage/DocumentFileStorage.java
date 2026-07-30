package com.ajt.backend.domain.document.storage;

import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentFileStorage {

    DocumentFileMutation moveToScope(
            String originalPath,
            String parsedPath,
            String targetScopeKey,
            long documentId
    ) throws IOException;

    String storeOriginal(String scopeKey, long documentId, MultipartFile file) throws IOException;

    String storeParsedMarkdown(String scopeKey, long documentId, String parsedMarkdown) throws IOException;

    String readText(String storedPath) throws IOException;

    Resource load(String storedPath);

    void delete(String storedPath) throws IOException;
}
