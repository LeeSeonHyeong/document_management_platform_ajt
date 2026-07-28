package com.ajt.backend.domain.document.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public class LocalDocumentFileStorage implements DocumentFileStorage {

    private final Path storageRoot;

    public LocalDocumentFileStorage(Path storageRoot) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    @Override
    public String storeOriginal(String scopeKey, long documentId, MultipartFile file) throws IOException {
        String extension = extensionOf(file.getOriginalFilename());
        String storedPath = "wiki/" + scopeKey + "/sources/" + documentId + "/original." + extension;
        Path target = resolve(storedPath);
        Files.createDirectories(target.getParent());
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return storedPath;
    }

    @Override
    public String storeParsedMarkdown(String scopeKey, long documentId, String parsedMarkdown) throws IOException {
        String storedPath = "wiki/" + scopeKey + "/sources/" + documentId + "/parsed.md";
        Path target = resolve(storedPath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, parsedMarkdown, StandardCharsets.UTF_8);
        return storedPath;
    }

    @Override
    public Resource load(String storedPath) {
        return new FileSystemResource(resolve(storedPath));
    }

    @Override
    public void delete(String storedPath) throws IOException {
        Files.deleteIfExists(resolve(storedPath));
    }

    private Path resolve(String storedPath) {
        Path path = storageRoot.resolve(storedPath).normalize();
        if (!path.startsWith(storageRoot)) {
            throw new IllegalArgumentException("저장 경로가 유효하지 않습니다.");
        }
        return path;
    }

    private String extensionOf(String originalFileName) {
        int extensionStart = originalFileName.lastIndexOf('.');
        return originalFileName.substring(extensionStart + 1);
    }
}
