package com.ajt.backend.domain.inquiry.dto;

import com.ajt.backend.domain.inquiry.InquiryReply;
import java.time.Instant;

/**
 * 문의 답변 응답입니다.
 * 답변 작성/수정 응답과 문의 상세의 answer 필드에서 공통으로 사용하며, 미답변이면 null로 내려갑니다.
 */
public record InquiryAnswerResponse(
        String answerId,
        String content,
        String adminId,
        String adminName,
        Instant createdAt,
        Instant updatedAt
) {
    public static InquiryAnswerResponse from(InquiryReply reply) {
        return new InquiryAnswerResponse(
                String.valueOf(reply.getId()),
                reply.getReply(),
                String.valueOf(reply.getResponder().getId()),
                reply.getResponder().getName(),
                reply.getCreatedAt(),
                reply.getUpdatedAt()
        );
    }
}
