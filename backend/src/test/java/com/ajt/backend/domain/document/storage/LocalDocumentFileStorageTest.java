package com.ajt.backend.domain.document.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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
    @DisplayName("합성한 원문 Markdown을 문서별 original.md 경로에 저장한다")
    void storesSynthesizedOriginalUnderDocumentPath() throws Exception {
        LocalDocumentFileStorage storage = new LocalDocumentFileStorage(storageRoot);

        String path = storage.storeSynthesizedOriginal("ALL", 817L, "# 관리자 지시\n\n> 4일로 변경");

        assertThat(path).isEqualTo("wiki/ALL/sources/817/original.md");
        assertThat(Files.readString(storageRoot.resolve(path), StandardCharsets.UTF_8))
                .isEqualTo("# 관리자 지시\n\n> 4일로 변경");
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

    @Test
    @DisplayName("stageOriginal은 최종 경로가 아닌 staging 경로에 저장하고 최종 경로는 건드리지 않는다(S15P11B106-146)")
    void stageOriginalWritesToStagingNotFinal() throws Exception {
        LocalDocumentFileStorage storage = new LocalDocumentFileStorage(storageRoot);
        // 기존 최종 원본이 이미 있다고 가정
        String existingFinal = storage.storeOriginal("ALL", 15L, new MockMultipartFile(
                "files", "old.md", "text/markdown", "old".getBytes()));

        StagedOriginalFile staged = storage.stageOriginal("ALL", 15L, new MockMultipartFile(
                "files", "new.md", "text/markdown", "new".getBytes()));

        assertThat(staged.finalPath()).isEqualTo("wiki/ALL/sources/15/original.md");
        assertThat(staged.stagingPath()).startsWith("wiki/ALL/sources/15/.staging/");
        // 새 파일은 staging에만 있고, 기존 최종 파일은 그대로다.
        assertThat(Files.readString(storageRoot.resolve(staged.stagingPath()))).isEqualTo("new");
        assertThat(Files.readString(storageRoot.resolve(existingFinal))).isEqualTo("old");
    }

    @Test
    @DisplayName("promoteStagedOriginal은 staging 파일을 최종 경로로 이동해 기존 파일을 덮어쓴다(S15P11B106-146)")
    void promoteMovesStagingOverFinal() throws Exception {
        LocalDocumentFileStorage storage = new LocalDocumentFileStorage(storageRoot);
        storage.storeOriginal("ALL", 15L, new MockMultipartFile(
                "files", "old.md", "text/markdown", "old".getBytes()));
        StagedOriginalFile staged = storage.stageOriginal("ALL", 15L, new MockMultipartFile(
                "files", "new.md", "text/markdown", "new".getBytes()));

        storage.promoteStagedOriginal(staged.stagingPath(), staged.finalPath());

        // 최종 경로가 새 내용으로 확정되고, staging 파일은 사라진다.
        assertThat(Files.readString(storageRoot.resolve(staged.finalPath()))).isEqualTo("new");
        assertThat(Files.exists(storageRoot.resolve(staged.stagingPath()))).isFalse();
    }

    @Test
    @DisplayName("원본과 파싱 파일을 새 scope로 옮긴 뒤 롤백하면 이전 경로를 복구한다")
    void movesFilesToScopeAndRestoresThemOnRollback() throws Exception {
        LocalDocumentFileStorage storage = new LocalDocumentFileStorage(storageRoot);
        String originalPath = storage.storeOriginal("ALL", 15L, new MockMultipartFile(
                "files", "취업규칙.pdf", "application/pdf", "original".getBytes()
        ));
        String parsedPath = storage.storeParsedMarkdown("ALL", 15L, "# parsed");

        DocumentFileMutation mutation = storage.moveToScope(originalPath, parsedPath, "D1-D3", 15L);

        assertThat(mutation.originalPath()).isEqualTo("wiki/D1-D3/sources/15/original.pdf");
        assertThat(mutation.parsedPath()).isEqualTo("wiki/D1-D3/sources/15/parsed.md");
        assertThat(Files.exists(storageRoot.resolve(originalPath))).isFalse();
        mutation.rollback();

        assertThat(Files.readString(storageRoot.resolve(originalPath))).isEqualTo("original");
        assertThat(Files.readString(storageRoot.resolve(parsedPath))).isEqualTo("# parsed");
    }
}
