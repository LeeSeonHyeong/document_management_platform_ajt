package com.ajt.backend.domain.inquiry.dto;

import org.springframework.core.io.Resource;

/**
 * 문의 첨부 이미지 다운로드에 필요한 파일 리소스와 응답 헤더 값입니다.
 * 컨트롤러가 이 값으로 Content-Type과 파일명을 설정합니다.
 */
public record InquiryAttachmentDownload(
        Resource resource,
        String fileName,
        String mimeType
) {
}
