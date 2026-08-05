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

    /**
     * 새 원본 파일을 최종 경로가 아닌 staging 경로에 저장합니다(S15P11B106-146).
     * 반환값의 finalPath는 커밋 성공 후 promoteStagedOriginal로 확정할 최종 경로입니다.
     */
    StagedOriginalFile stageOriginal(String scopeKey, long documentId, MultipartFile file) throws IOException;

    /** staging 파일을 최종 경로로 이동해 교체를 확정합니다(기존 최종 파일이 있으면 덮어씀). */
    void promoteStagedOriginal(String stagingPath, String finalPath) throws IOException;

    String storeParsedMarkdown(String scopeKey, long documentId, String parsedMarkdown) throws IOException;

    String storeSynthesizedOriginal(String scopeKey, long documentId, String markdown) throws IOException;

    String readText(String storedPath) throws IOException;

    Resource load(String storedPath);

    void delete(String storedPath) throws IOException;
}
