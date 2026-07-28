package com.ajt.backend.domain.inquiry.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 문의 답변 작성/수정 요청 본문입니다.
 * 답변 임시 저장이 없으므로 내용은 항상 필수입니다.
 */
public record InquiryAnswerRequest(
        @NotBlank(message = "답변 내용을 입력해주세요.")
        String content
) {
}
