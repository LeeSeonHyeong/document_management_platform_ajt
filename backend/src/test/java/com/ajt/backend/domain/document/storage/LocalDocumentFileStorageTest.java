package com.ajt.backend.domain.document.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

@DisplayName("원본문서 로컬 파일 저장소")
class LocalDocumentFileStorageTest {

    @TempDir
    Path storageRoot;

    @Test
    @DisplayName("원본 파일을 scope와 문서 ID 경로에 저장한다")
    void storesOriginalFileUnderScopeAndDocumentId() throws Exception {
        LocalDocumentFileStorage storage = new LocalDocumentFileStorage(storageRoot);
        MockMultipartFile file = new MockMultipartFile(
                "files", "취업규칙.pdf", "application/pdf", "original".getBytes()
        );

        String storedPath = storage.storeOriginal("D1-D2", 15L, file);

        assertThat(storedPath).isEqualTo("wiki/D1-D2/sources/15/original.pdf");
        assertThat(Files.readString(storageRoot.resolve(storedPath))).isEqualTo("original");
    }

    @Test
    @DisplayName("파싱 Markdown을 문서별 경로에 저장한다")
    void storesParsedMarkdownUnderDocumentPath() throws Exception {
        LocalDocumentFileStorage storage = new LocalDocumentFileStorage(storageRoot);

        String storedPath = storage.storeParsedMarkdown("ALL", 15L, "# 취업규칙");

        assertThat(storedPath).isEqualTo("wiki/ALL/sources/15/parsed.md");
        assertThat(Files.readString(storageRoot.resolve(storedPath))).isEqualTo("# 취업규칙");
    }

    @Test
    @DisplayName("저장한 파일을 상대 경로로 삭제한다")
    void deletesStoredFile() throws Exception {
        LocalDocumentFileStorage storage = new LocalDocumentFileStorage(storageRoot);
        String storedPath = storage.storeParsedMarkdown("ALL", 15L, "# 취업규칙");

        storage.delete(storedPath);

        assertThat(Files.exists(storageRoot.resolve(storedPath))).isFalse();
    }

    @Test
    @DisplayName("저장한 원본 파일을 Resource로 불러온다")
    void loadsStoredOriginalFileAsResource() throws Exception {
        LocalDocumentFileStorage storage = new LocalDocumentFileStorage(storageRoot);
        MockMultipartFile file = new MockMultipartFile(
                "files", "취업규칙.md", "text/markdown", "# original".getBytes()
        );
        String storedPath = storage.storeOriginal("ALL", 15L, file);

        Resource resource = storage.load(storedPath);

        assertThat(resource.exists()).isTrue();
        assertThat(resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("# original");
    }
}
