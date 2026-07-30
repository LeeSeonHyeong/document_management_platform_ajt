package com.ajt.backend.domain.wiki.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("로컬 Wiki 파일 저장소")
class LocalWikiFileStorageTest {

    @TempDir
    Path storageRoot;

    @Test
    @DisplayName("Wiki 본문을 공간별 pages 경로에 저장하고 다시 읽는다")
    void storesAndReadsWikiMarkdown() throws IOException {
        LocalWikiFileStorage storage = new LocalWikiFileStorage(storageRoot);

        String storedPath = storage.storeWikiMarkdown("ALL", 101L, "# 휴가 규정");

        assertThat(storedPath).isEqualTo("wiki/ALL/pages/101.md");
        assertThat(Files.readString(storageRoot.resolve(storedPath), StandardCharsets.UTF_8))
                .isEqualTo("# 휴가 규정");
        assertThat(storage.readWikiMarkdown(storedPath)).isEqualTo("# 휴가 규정");
    }

    @Test
    @DisplayName("본문 파일이 없으면 빈 문자열을 반환한다")
    void returnsEmptyStringForMissingWiki() throws IOException {
        LocalWikiFileStorage storage = new LocalWikiFileStorage(storageRoot);

        assertThat(storage.readWikiMarkdown("wiki/ALL/pages/999.md")).isEmpty();
    }

    @Test
    @DisplayName("본문 파일을 삭제한다")
    void deletesWikiMarkdown() throws IOException {
        LocalWikiFileStorage storage = new LocalWikiFileStorage(storageRoot);
        String storedPath = storage.storeWikiMarkdown("ALL", 101L, "# 휴가 규정");

        storage.deleteWikiMarkdown(storedPath);

        assertThat(Files.exists(storageRoot.resolve(storedPath))).isFalse();
    }

    @Test
    @DisplayName("목차가 없는 새 공간은 빈 목차를 반환한다")
    void returnsEmptyIndexForNewScope() throws IOException {
        LocalWikiFileStorage storage = new LocalWikiFileStorage(storageRoot);

        assertThat(storage.readIndex("ALL")).isEqualTo(LocalWikiFileStorage.EMPTY_INDEX);
    }

    @Test
    @DisplayName("목차를 저장하고 다시 읽는다")
    void storesAndReadsIndex() throws IOException {
        LocalWikiFileStorage storage = new LocalWikiFileStorage(storageRoot);

        String storedPath = storage.storeIndex("ALL", "# 목차\n\n- [휴가 규정](pages/101.md)");

        assertThat(storedPath).isEqualTo("wiki/ALL/index.md");
        assertThat(storage.readIndex("ALL")).isEqualTo("# 목차\n\n- [휴가 규정](pages/101.md)");
    }

    @Test
    @DisplayName("저장 루트를 벗어나는 경로는 거부한다")
    void rejectsPathOutsideRoot() {
        LocalWikiFileStorage storage = new LocalWikiFileStorage(storageRoot);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> storage.readWikiMarkdown("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("저장 경로");
    }
}
