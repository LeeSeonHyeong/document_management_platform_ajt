package com.ajt.backend.domain.wiki.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public WikiFileMutation beginMutation() {
        return new LocalWikiFileMutation();
    }

    @Override
    public void storeWikiMarkdown(String wikiPath, String contentMarkdown) throws IOException {
        writeString(wikiPath, contentMarkdown);
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
        Path temporary = Files.createTempFile(target.getParent(), ".wiki-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Wiki 파일 시스템이 원자적 교체를 지원하지 않습니다: " + target, exception);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path resolve(String storedPath) {
        Path path = storageRoot.resolve(storedPath).normalize();
        if (!path.startsWith(storageRoot)) {
            throw new IllegalArgumentException("저장 경로가 유효하지 않습니다.");
        }
        return path;
    }

    private final class LocalWikiFileMutation implements WikiFileMutation {

        private final Map<Path, byte[]> originalContents = new LinkedHashMap<>();

        @Override
        public void storeWikiMarkdown(String wikiPath, String contentMarkdown) throws IOException {
            write(wikiPath, contentMarkdown);
        }

        @Override
        public void deleteWikiMarkdown(String wikiPath) throws IOException {
            Path target = resolve(wikiPath);
            remember(target);
            Files.deleteIfExists(target);
        }

        @Override
        public String storeIndex(String scopeKey, String indexMarkdown) throws IOException {
            String storedPath = indexPathOf(scopeKey);
            write(storedPath, indexMarkdown);
            return storedPath;
        }

        @Override
        public void rollback() throws IOException {
            List<Map.Entry<Path, byte[]>> snapshots = new ArrayList<>(originalContents.entrySet());
            for (int index = snapshots.size() - 1; index >= 0; index--) {
                Map.Entry<Path, byte[]> snapshot = snapshots.get(index);
                if (snapshot.getValue() == null) {
                    Files.deleteIfExists(snapshot.getKey());
                } else {
                    writeBytes(snapshot.getKey(), snapshot.getValue());
                }
            }
            originalContents.clear();
        }

        @Override
        public void discardBackup() {
            originalContents.clear();
        }

        private void write(String storedPath, String content) throws IOException {
            Path target = resolve(storedPath);
            remember(target);
            writeString(storedPath, content);
        }

        private void remember(Path target) throws IOException {
            if (!originalContents.containsKey(target)) {
                originalContents.put(target, Files.exists(target) ? Files.readAllBytes(target) : null);
            }
        }

        private void writeBytes(Path target, byte[] content) throws IOException {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".wiki-", ".tmp");
            try {
                Files.write(temporary, content);
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Wiki 파일 시스템이 원자적 교체를 지원하지 않습니다: " + target, exception);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
