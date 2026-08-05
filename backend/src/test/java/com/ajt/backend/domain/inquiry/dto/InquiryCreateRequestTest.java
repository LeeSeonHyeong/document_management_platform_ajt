package com.ajt.backend.domain.inquiry.dto;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("문의 등록 요청 입력 검증(S15P11B106-273)")
class InquiryCreateRequestTest {

    @Test
    @DisplayName("제목이 30자를 초과하면 INVALID_INQUIRY로 막는다")
    void rejectsTitleLongerThan30() {
        assertThatThrownBy(() -> InquiryCreateRequest.of(1L, "가".repeat(31), "내용", "normal", List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INQUIRY);
    }

    @Test
    @DisplayName("제목에 이모지가 있으면 INVALID_INQUIRY로 막는다")
    void rejectsTitleWithEmoji() {
        assertThatThrownBy(() -> InquiryCreateRequest.of(1L, "연차 문의😀", "내용", "normal", List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INQUIRY);
    }

    @Test
    @DisplayName("제목의 물음표·느낌표 등 일반 문장부호는 허용한다")
    void allowsPunctuationInTitle() {
        assertThatCode(() -> InquiryCreateRequest.of(1L, "연차 문의?!", "내용", "normal", List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("내용이 500자를 초과하면 INVALID_INQUIRY로 막는다")
    void rejectsContentLongerThan500() {
        assertThatThrownBy(() -> InquiryCreateRequest.of(1L, "제목", "가".repeat(501), "normal", List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INQUIRY);
    }
}
