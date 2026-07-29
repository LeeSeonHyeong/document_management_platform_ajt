package com.ajt.backend.domain.schedule.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public class LocalScheduleSourceFileStorage implements ScheduleSourceFileStorage {

    private static final String ROOT_DIRECTORY = "schedule-sources";

    private final Path storageRoot;

    public LocalScheduleSourceFileStorage(Path storageRoot) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    @Override
    public String storeOriginal(String sourceGroupKey, MultipartFile file) throws IOException {
        String storedPath = groupPath(sourceGroupKey)
                + "/original/source." + extensionOf(file.getOriginalFilename());
        Path target = resolve(storedPath);
        Files.createDirectories(target.getParent());
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return storedPath;
    }

    @Override
    public String storeParsedMarkdown(String sourceGroupKey, String parsedMarkdown) throws IOException {
        String storedPath = groupPath(sourceGroupKey) + "/parsed/content.md";
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
    public void deleteSourceGroup(String sourceGroupKey) throws IOException {
        Path groupDirectory = resolve(groupPath(sourceGroupKey));
        if (!Files.exists(groupDirectory)) {
            return;
        }
        try (var paths = Files.walk(groupDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private String groupPath(String sourceGroupKey) {
        return ROOT_DIRECTORY + "/" + requireSafeKey(sourceGroupKey);
    }

    /**
     * sourceGroupKey가 경로 구분자나 상위 참조를 포함하면 저장 경로를 벗어날 수 있어 거부합니다.
     */
    private String requireSafeKey(String sourceGroupKey) {
        if (sourceGroupKey == null || sourceGroupKey.isBlank()
                || !sourceGroupKey.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("일정 원본문서 그룹 키가 유효하지 않습니다.");
        }
        return sourceGroupKey;
    }

    private Path resolve(String storedPath) {
        Path path = storageRoot.resolve(storedPath).normalize();
        if (!path.startsWith(storageRoot)) {
            throw new IllegalArgumentException("저장 경로가 유효하지 않습니다.");
        }
        return path;
    }

    private String extensionOf(String originalFileName) {
        if (originalFileName == null) {
            throw new IllegalArgumentException("원본 파일명이 없습니다.");
        }
        int extensionStart = originalFileName.lastIndexOf('.');
        if (extensionStart < 0 || extensionStart == originalFileName.length() - 1) {
            throw new IllegalArgumentException("원본 파일 확장자가 없습니다.");
        }
        return originalFileName.substring(extensionStart + 1).toLowerCase(Locale.ROOT);
    }
}
