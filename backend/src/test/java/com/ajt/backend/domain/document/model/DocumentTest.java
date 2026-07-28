package com.ajt.backend.domain.document.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("원본문서 처리 상태")
class DocumentTest {

    @Test
    @DisplayName("업로드된 문서는 파싱 성공 후 처리 대기 상태가 된다")
    void movesFromUploadedToProcessingAfterParseSuccess() {
        Document document = Document.uploaded(
                10L,
                3L,
                "D1-D2",
                "취업규칙.pdf",
                "documents/D1-D2/1/original.pdf",
                "application/pdf",
                100L
        );

        document.startParsing();
        document.completeParsing("documents/D1-D2/1/parsed.md");

        assertThat(document.status()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(document.parsedPath()).isEqualTo("documents/D1-D2/1/parsed.md");
        assertThat(document.failureReason()).isNull();
    }

    @Test
    @DisplayName("파싱 실패 문서는 실패 사유와 함께 실패 상태가 된다")
    void movesToFailedWhenParsingFails() {
        Document document = Document.uploaded(
                10L,
                3L,
                "ALL",
                "규정.md",
                "documents/ALL/1/original.md",
                "text/markdown",
                100L
        );

        document.startParsing();
        document.failParsing("FastAPI 응답 시간이 초과되었습니다.");

        assertThat(document.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.failureReason()).isEqualTo("FastAPI 응답 시간이 초과되었습니다.");
    }

    @Test
    @DisplayName("업로드 상태가 아니면 파싱을 시작할 수 없다")
    void rejectsParsingStartOutsideUploadedState() {
        Document document = Document.uploaded(
                10L,
                3L,
                "ALL",
                "규정.md",
                "documents/ALL/1/original.md",
                "text/markdown",
                100L
        );
        document.startParsing();

        assertThatThrownBy(document::startParsing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("UPLOADED 상태의 문서만 파싱할 수 있습니다.");
    }
}
