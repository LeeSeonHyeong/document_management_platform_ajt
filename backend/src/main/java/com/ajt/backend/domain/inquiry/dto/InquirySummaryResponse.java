package com.ajt.backend.domain.inquiry.dto;

import com.ajt.backend.domain.inquiry.Inquiry;
import java.time.Instant;

/**
 * 문의 목록의 한 줄 정보입니다.
 * 목록 화면에서 필요한 요약 값만 담고 본문·첨부·답변은 상세 조회에서 제공합니다.
 */
public record InquirySummaryResponse(
        String inquiryId,
        String title,
        InquiryAuthorResponse author,
        InquiryAssigneeResponse assignee,
        String priority,
        String status,
        Instant createdAt
) {
    public static InquirySummaryResponse from(Inquiry inquiry) {
        return new InquirySummaryResponse(
                String.valueOf(inquiry.getId()),
                inquiry.getTitle(),
                InquiryAuthorResponse.from(inquiry.getAuthor()),
                InquiryAssigneeResponse.from(inquiry.getAssignee()),
                inquiry.getPriority().apiValue(),
                inquiry.getStatus().apiValue(),
                inquiry.getCreatedAt()
        );
    }
}
