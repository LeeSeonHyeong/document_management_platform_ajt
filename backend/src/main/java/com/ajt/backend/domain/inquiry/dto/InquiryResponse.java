package com.ajt.backend.domain.inquiry.dto;

import com.ajt.backend.domain.inquiry.Inquiry;
import com.ajt.backend.domain.inquiry.InquiryReply;
import java.time.Instant;
import java.util.List;

/**
 * 문의 상세 응답입니다.
 * 등록 성공(201) 응답과 상세 조회(200) 응답에서 공통으로 사용합니다.
 * 미답변 문의는 answer가 null입니다.
 */
public record InquiryResponse(
        String inquiryId,
        String title,
        String content,
        String priority,
        String status,
        InquiryAuthorResponse author,
        InquiryAssigneeResponse assignee,
        List<InquiryAttachmentResponse> attachments,
        InquiryAnswerResponse answer,
        Instant createdAt
) {
    public static InquiryResponse from(Inquiry inquiry, InquiryReply reply) {
        long inquiryId = inquiry.getId();
        List<InquiryAttachmentResponse> attachments = inquiry.getAttachmentRefs().stream()
                .map(attachment -> InquiryAttachmentResponse.from(inquiryId, attachment))
                .toList();
        return new InquiryResponse(
                String.valueOf(inquiryId),
                inquiry.getTitle(),
                inquiry.getContent(),
                inquiry.getPriority().apiValue(),
                inquiry.getStatus().apiValue(),
                InquiryAuthorResponse.from(inquiry.getAuthor()),
                InquiryAssigneeResponse.from(inquiry.getAssignee()),
                attachments,
                reply == null ? null : InquiryAnswerResponse.from(reply),
                inquiry.getCreatedAt()
        );
    }
}
