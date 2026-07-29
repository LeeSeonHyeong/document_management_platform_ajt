package com.ajt.backend.domain.inquiry.dto;

import com.ajt.backend.domain.inquiry.InquiryAttachment;

/**
 * 문의 첨부 이미지 응답입니다.
 * 실제 파일은 downloadUrl로 별도 조회하며, 저장 경로 같은 내부 값은 노출하지 않습니다.
 */
public record InquiryAttachmentResponse(
        String attachmentId,
        String fileName,
        String mimeType,
        long size,
        String downloadUrl
) {
    public static InquiryAttachmentResponse from(long inquiryId, InquiryAttachment attachment) {
        return new InquiryAttachmentResponse(
                attachment.attachmentId(),
                attachment.originalFileName(),
                attachment.mimeType(),
                attachment.size(),
                "/api/v1/inquiries/%d/attachments/%s".formatted(inquiryId, attachment.attachmentId())
        );
    }
}
