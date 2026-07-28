package com.ajt.backend.domain.document.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

@DisplayName("Wiki 원본문서 업로드 요청")
class DocumentUploadRequestTest {

    @Test
    @DisplayName("유효한 부서 공개 업로드 요청을 만든다")
    void createsDepartmentUploadRequest() {
        DocumentUploadRequest request = DocumentUploadRequest.of(
                List.of(file("rules.pdf", "application/pdf", 10)),
                3L,
                "department",
                List.of(2L, 1L, 2L)
        );

        assertThat(request.documentCategoryId()).isEqualTo(3L);
        assertThat(request.scopeKey().value()).isEqualTo("D1-D2");
        assertThat(request.files()).hasSize(1);
    }

    @Test
    @DisplayName("파일이 없으면 업로드 요청을 거부한다")
    void rejectsEmptyFiles() {
        assertThatThrownBy(() -> DocumentUploadRequest.of(List.of(), 3L, "all", List.of()))
                .isInstanceOf(DocumentUploadValidationException.class)
                .hasMessage("파일을 하나 이상 업로드해야 합니다.");
    }

    @Test
    @DisplayName("허용하지 않은 확장자의 파일을 거부한다")
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> DocumentUploadRequest.of(
                List.of(file("rules.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 10)),
                3L,
                "all",
                List.of()
        )).isInstanceOf(DocumentUploadValidationException.class)
                .hasMessage("TXT, MD, PDF, DOCX 파일만 업로드할 수 있습니다.");
    }

    @Test
    @DisplayName("허용하지 않은 MIME 타입의 파일을 거부한다")
    void rejectsUnsupportedMimeType() {
        assertThatThrownBy(() -> DocumentUploadRequest.of(
                List.of(file("rules.pdf", "image/png", 10)),
                3L,
                "all",
                List.of()
        )).isInstanceOf(DocumentUploadValidationException.class)
                .hasMessage("TXT, MD, PDF, DOCX 파일만 업로드할 수 있습니다.");
    }

    @Test
    @DisplayName("파일 하나가 20MB를 초과하면 업로드 요청을 거부한다")
    void rejectsFileLargerThan20Mb() {
        assertThatThrownBy(() -> DocumentUploadRequest.of(
                List.of(file("rules.pdf", "application/pdf", 20 * 1024 * 1024 + 1)),
                3L,
                "all",
                List.of()
        )).isInstanceOf(DocumentUploadValidationException.class)
                .hasMessage("파일당 최대 크기는 20MB입니다.");
    }

    @Test
    @DisplayName("파일이 20개를 초과하면 업로드 요청을 거부한다")
    void rejectsMoreThan20Files() {
        List<MultipartFile> files = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> file("rules-" + index + ".txt", "text/plain", 1))
                .toList();

        assertThatThrownBy(() -> DocumentUploadRequest.of(files, 3L, "all", List.of()))
                .isInstanceOf(DocumentUploadValidationException.class)
                .hasMessage("파일은 최대 20개까지 업로드할 수 있습니다.");
    }

    @Test
    @DisplayName("요청 전체 크기가 100MB를 초과하면 업로드 요청을 거부한다")
    void rejectsTotalSizeLargerThan100Mb() {
        List<MultipartFile> files = List.of(
                file("one.pdf", "application/pdf", 20 * 1024 * 1024),
                file("two.pdf", "application/pdf", 20 * 1024 * 1024),
                file("three.pdf", "application/pdf", 20 * 1024 * 1024),
                file("four.pdf", "application/pdf", 20 * 1024 * 1024),
                file("five.pdf", "application/pdf", 20 * 1024 * 1024),
                file("six.pdf", "application/pdf", 1)
        );

        assertThatThrownBy(() -> DocumentUploadRequest.of(files, 3L, "all", List.of()))
                .isInstanceOf(DocumentUploadValidationException.class)
                .hasMessage("요청 전체 파일 크기는 최대 100MB입니다.");
    }

    private MultipartFile file(String name, String contentType, long size) {
        MultipartFile file = mock(MultipartFile.class);
        given(file.getOriginalFilename()).willReturn(name);
        given(file.getContentType()).willReturn(contentType);
        given(file.getSize()).willReturn(size);
        given(file.isEmpty()).willReturn(size == 0);
        return file;
    }
}
