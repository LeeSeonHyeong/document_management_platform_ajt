package com.ajt.backend.domain.wiki.storage;

import com.ajt.backend.domain.wiki.model.Wiki;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class LocalWikiFileStorage implements WikiFileStorage {

    /**
     * 목차 파일이 아직 없는 새 Wiki 공간에 사용하는 기본 목차입니다.
     * FastAPI 변환 요청의 currentIndex는 빈 문자열을 허용하지 않습니다.
     */
    static final String EMPTY_INDEX = "# 목차";

    private final Path storageRoot;

    public LocalWikiFileStorage(Path storageRoot) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    @Override
    public String storeWikiMarkdown(String scopeKey, long wikiId, String contentMarkdown) throws IOException {
        String storedPath = Wiki.storagePathOf(scopeKey, wikiId);
        writeString(storedPath, contentMarkdown);
        return storedPath;
    }

    @Override
    public String readWikiMarkdown(String wikiPath) throws IOException {
        Path path = resolve(wikiPath);
        if (!Files.exists(path)) {
            return "";
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Override
    public void deleteWikiMarkdown(String wikiPath) throws IOException {
        Files.deleteIfExists(resolve(wikiPath));
    }

    @Override
    public String storeIndex(String scopeKey, String indexMarkdown) throws IOException {
        String storedPath = indexPathOf(scopeKey);
        writeString(storedPath, indexMarkdown);
        return storedPath;
    }

    @Override
    public String readIndex(String scopeKey) throws IOException {
        Path path = resolve(indexPathOf(scopeKey));
        if (!Files.exists(path)) {
            return EMPTY_INDEX;
        }
        String index = Files.readString(path, StandardCharsets.UTF_8);
        return index.isBlank() ? EMPTY_INDEX : index;
    }

    private static String indexPathOf(String scopeKey) {
        return "wiki/" + scopeKey + "/index.md";
    }

    private void writeString(String storedPath, String content) throws IOException {
        Path target = resolve(storedPath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private Path resolve(String storedPath) {
        Path path = storageRoot.resolve(storedPath).normalize();
        if (!path.startsWith(storageRoot)) {
            throw new IllegalArgumentException("저장 경로가 유효하지 않습니다.");
        }
        return path;
    }
}
