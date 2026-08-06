package com.ajt.backend.domain.document.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ajt.backend.domain.document.model.Document;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("원본문서 목록 항목 응답")
class DocumentSummaryResponseTest {

    /**
     * 확정 전 업로드(카테고리 미지정)의 회귀 방지입니다(S15P11B106-276).
     *
     * <p>{@code String.valueOf(null)}은 문자열 "null"을 만든다. 그 값이 내려가면 프론트는
     * 값이 있는 것으로 읽어 분류가 끝난 문서로 판단하고, 카테고리를 고르지 않았는데도
     * 「모두 지정되었습니다」로 표시한다.
     */
    @Test
    @DisplayName("카테고리가 없으면 documentCategoryId를 null로 내려준다(문자열 \"null\"이 아니다)")
    void keepsNullCategoryIdAsNull() {
        DocumentSummaryResponse response = DocumentSummaryResponse.from(
                Document.uploaded(10L, null, "ALL", "rule.pdf", "wiki/ALL/sources/15/original.pdf",
                        "application/pdf", 1024L),
                null,
                "all",
                List.of(),
                new DocumentUploaderResponse("10", "최고관리자")
        );

        assertThat(response.documentCategoryId()).isNull();
        assertThat(response.documentCategoryName()).isNull();
    }

    @Test
    @DisplayName("카테고리가 있으면 문자열로 내려준다")
    void serializesCategoryIdAsString() {
        DocumentSummaryResponse response = DocumentSummaryResponse.from(
                Document.uploaded(10L, 7L, "ALL", "rule.pdf", "wiki/ALL/sources/15/original.pdf",
                        "application/pdf", 1024L),
                "취업규칙",
                "all",
                List.of(),
                new DocumentUploaderResponse("10", "최고관리자")
        );

        assertThat(response.documentCategoryId()).isEqualTo("7");
        assertThat(response.documentCategoryName()).isEqualTo("취업규칙");
    }
}
