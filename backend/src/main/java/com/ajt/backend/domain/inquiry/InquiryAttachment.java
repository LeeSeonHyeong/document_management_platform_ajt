package com.ajt.backend.domain.inquiry;

/**
 * 문의 첨부 이미지 한 건의 메타데이터입니다.
 * 별도 파일 테이블 없이 inquiry.attachment_refs JSON 배열에 그대로 저장합니다. (DR-020)
 *
 * @param attachmentId     첨부 식별자. 다운로드 API의 attachmentId 경로 변수로 사용합니다.
 * @param originalFileName 사용자가 업로드한 원래 파일명. 저장 경로 구성에는 사용하지 않습니다.
 * @param storedPath       저장소 루트를 제외한 상대 경로
 * @param mimeType         이미지 MIME 타입 (image/png, image/jpeg)
 * @param size             파일 크기(byte)
 */
public record InquiryAttachment(
        String attachmentId,
        String originalFileName,
        String storedPath,
        String mimeType,
        long size
) {
}
