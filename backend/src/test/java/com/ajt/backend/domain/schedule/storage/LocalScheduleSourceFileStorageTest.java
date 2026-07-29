package com.ajt.backend.domain.schedule.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class LocalScheduleSourceFileStorageTest {

    @TempDir
    Path storageRoot;

    private MockMultipartFile file(String originalFileName) {
        return new MockMultipartFile(
                "file", originalFileName, "text/csv", "일정,시작\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("원본문서는 schedule-sources/{key}/original/source.{ext} 로 저장된다")
    void storesOriginalUnderSourceGroupDirectory() throws IOException {
        LocalScheduleSourceFileStorage storage = new LocalScheduleSourceFileStorage(storageRoot);

        String storedPath = storage.storeOriginal("schedule-source-20260728-01", file("8월일정.XLSX"));

        assertThat(storedPath)
                .isEqualTo("schedule-sources/schedule-source-20260728-01/original/source.xlsx");
        assertThat(Files.exists(storageRoot.resolve(storedPath))).isTrue();
    }

    @Test
    @DisplayName("파싱 결과는 schedule-sources/{key}/parsed/content.md 로 저장된다")
    void storesParsedMarkdown() throws IOException {
        LocalScheduleSourceFileStorage storage = new LocalScheduleSourceFileStorage(storageRoot);

        String storedPath = storage.storeParsedMarkdown("grp-1", "# 8월 일정\n본문");

        assertThat(storedPath).isEqualTo("schedule-sources/grp-1/parsed/content.md");
        assertThat(Files.readString(storageRoot.resolve(storedPath))).isEqualTo("# 8월 일정\n본문");
    }

    @Test
    @DisplayName("원본문서 그룹을 삭제하면 원본·파싱 파일과 디렉터리가 함께 제거된다")
    void deletesSourceGroupDirectory() throws IOException {
        LocalScheduleSourceFileStorage storage = new LocalScheduleSourceFileStorage(storageRoot);
        storage.storeOriginal("grp-1", file("일정.csv"));
        storage.storeParsedMarkdown("grp-1", "# 일정");

        storage.deleteSourceGroup("grp-1");

        assertThat(Files.exists(storageRoot.resolve("schedule-sources/grp-1"))).isFalse();
    }

    @Test
    @DisplayName("이미 없는 원본문서 그룹 삭제는 실패하지 않는다")
    void deleteSourceGroupIsIdempotent() {
        LocalScheduleSourceFileStorage storage = new LocalScheduleSourceFileStorage(storageRoot);

        assertThatCode(() -> storage.deleteSourceGroup("grp-none"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("경로를 벗어나는 그룹 키는 거부한다")
    void rejectsSourceGroupKeyEscapingRoot() {
        LocalScheduleSourceFileStorage storage = new LocalScheduleSourceFileStorage(storageRoot);

        assertThatThrownBy(() -> storage.storeParsedMarkdown("../../etc", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.storeParsedMarkdown("grp/1", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("확장자가 없는 원본 파일명은 거부한다")
    void rejectsFileNameWithoutExtension() {
        LocalScheduleSourceFileStorage storage = new LocalScheduleSourceFileStorage(storageRoot);

        assertThatThrownBy(() -> storage.storeOriginal("grp-1", file("일정")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
