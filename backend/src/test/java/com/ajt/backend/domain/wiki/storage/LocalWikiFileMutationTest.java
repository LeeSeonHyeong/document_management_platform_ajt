package com.ajt.backend.domain.wiki.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("로컬 Wiki 파일 반영 보상")
class LocalWikiFileMutationTest {

    @TempDir
    Path storageRoot;

    @Test
    @DisplayName("기존 본문과 목차를 바꾼 뒤 롤백하면 반영 전 상태로 복구한다")
    void restoresExistingFilesOnRollback() throws Exception {
        LocalWikiFileStorage storage = new LocalWikiFileStorage(storageRoot);
        storage.storeWikiMarkdown("wiki/ALL/pages/leave.md", "기존 본문");
        storage.storeIndex("ALL", "기존 목차");
        WikiFileMutation mutation = storage.beginMutation();

        mutation.storeWikiMarkdown("wiki/ALL/pages/leave.md", "새 본문");
        mutation.storeIndex("ALL", "새 목차");
        mutation.rollback();

        assertThat(storage.readWikiMarkdown("wiki/ALL/pages/leave.md")).isEqualTo("기존 본문");
        assertThat(storage.readIndex("ALL")).isEqualTo("기존 목차");
    }

    @Test
    @DisplayName("새 본문 파일을 만든 뒤 롤백하면 파일을 삭제한다")
    void deletesNewFileOnRollback() throws Exception {
        LocalWikiFileStorage storage = new LocalWikiFileStorage(storageRoot);
        WikiFileMutation mutation = storage.beginMutation();

        mutation.storeWikiMarkdown("wiki/ALL/pages/new.md", "새 본문");
        mutation.rollback();

        assertThat(Files.exists(storageRoot.resolve("wiki/ALL/pages/new.md"))).isFalse();
    }

    @Test
    @DisplayName("삭제한 기존 본문을 롤백하면 원래 바이트 그대로 복구한다")
    void restoresDeletedFileOnRollback() throws Exception {
        LocalWikiFileStorage storage = new LocalWikiFileStorage(storageRoot);
        Path wikiPath = storageRoot.resolve("wiki/ALL/pages/leave.md");
        Files.createDirectories(wikiPath.getParent());
        byte[] original = "기존 본문".getBytes(StandardCharsets.UTF_8);
        Files.write(wikiPath, original);
        WikiFileMutation mutation = storage.beginMutation();

        mutation.deleteWikiMarkdown("wiki/ALL/pages/leave.md");
        mutation.rollback();

        assertThat(Files.readAllBytes(wikiPath)).isEqualTo(original);
    }
}
