package com.ajt.backend.domain.document.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public class LocalDocumentFileStorage implements DocumentFileStorage {

    private final Path storageRoot;

    public LocalDocumentFileStorage(Path storageRoot) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    @Override
    public DocumentFileMutation moveToScope(String originalPath, String parsedPath, String targetScopeKey, long documentId)
            throws IOException {
        String movedOriginal = "wiki/" + targetScopeKey + "/sources/" + documentId + "/original." + extensionOf(originalPath);
        String movedParsed = parsedPath == null ? null : "wiki/" + targetScopeKey + "/sources/" + documentId + "/parsed.md";
        move(originalPath, movedOriginal);
        try {
            if (movedParsed != null) {
                move(parsedPath, movedParsed);
            }
        } catch (IOException exception) {
            move(movedOriginal, originalPath);
            throw exception;
        }
        return new ScopeMove(originalPath, parsedPath, movedOriginal, movedParsed);
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
    public StagedOriginalFile stageOriginal(String scopeKey, long documentId, MultipartFile file) throws IOException {
        String extension = extensionOf(file.getOriginalFilename());
        String finalPath = "wiki/" + scopeKey + "/sources/" + documentId + "/original." + extension;
        // 최종 경로와 충돌하지 않도록 문서별 .staging 하위에 고유 이름으로 저장한다.
        String stagingPath =
                "wiki/" + scopeKey + "/sources/" + documentId + "/.staging/" + UUID.randomUUID() + "." + extension;
        Path target = resolve(stagingPath);
        Files.createDirectories(target.getParent());
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new StagedOriginalFile(stagingPath, finalPath);
    }

    @Override
    public void promoteStagedOriginal(String stagingPath, String finalPath) throws IOException {
        Path source = resolve(stagingPath);
        Path target = resolve(finalPath);
        Files.createDirectories(target.getParent());
        // 최종 경로에 기존 파일이 있으면 덮어쓴다(같은 확장자 교체). ATOMIC_MOVE는 REPLACE_EXISTING과
        // 함께 일부 플랫폼에서 지원되지 않으므로 일반 move를 쓴다(staging과 최종은 같은 저장 루트).
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
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
    public String readText(String storedPath) throws IOException {
        return Files.readString(resolve(storedPath), StandardCharsets.UTF_8);
    }

    @Override
    public Resource load(String storedPath) {
        return new FileSystemResource(resolve(storedPath));
    }

    @Override
    public void delete(String storedPath) throws IOException {
        Files.deleteIfExists(resolve(storedPath));
    }

    private void move(String sourcePath, String targetPath) throws IOException {
        Path source = resolve(sourcePath);
        Path target = resolve(targetPath);
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path resolve(String storedPath) {
        Path path = storageRoot.resolve(storedPath).normalize();
        if (!path.startsWith(storageRoot)) {
            throw new IllegalArgumentException("저장 경로가 유효하지 않습니다.");
        }
        return path;
    }

    private String extensionOf(String fileName) {
        int extensionStart = fileName.lastIndexOf('.');
        return fileName.substring(extensionStart + 1);
    }

    private final class ScopeMove implements DocumentFileMutation {

        private final String previousOriginalPath;
        private final String previousParsedPath;
        private final String originalPath;
        private final String parsedPath;

        private ScopeMove(String previousOriginalPath, String previousParsedPath, String originalPath, String parsedPath) {
            this.previousOriginalPath = previousOriginalPath;
            this.previousParsedPath = previousParsedPath;
            this.originalPath = originalPath;
            this.parsedPath = parsedPath;
        }

        @Override public String originalPath() { return originalPath; }
        @Override public String parsedPath() { return parsedPath; }

        @Override
        public void rollback() throws IOException {
            if (parsedPath != null) move(parsedPath, previousParsedPath);
            move(originalPath, previousOriginalPath);
        }

        @Override public void discardBackup() { }
    }
}
