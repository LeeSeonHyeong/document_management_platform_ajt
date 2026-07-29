package com.ajt.backend.domain.inquiry.storage;

import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * 문의 첨부 이미지 저장소입니다.
 * 파일은 inquiries/{inquiryId}/attachments 아래에 저장합니다. (DR-015)
 */
public interface InquiryFileStorage {

    String storeAttachment(long inquiryId, String attachmentId, MultipartFile file) throws IOException;

    Resource load(String storedPath);

    void delete(String storedPath) throws IOException;
}
