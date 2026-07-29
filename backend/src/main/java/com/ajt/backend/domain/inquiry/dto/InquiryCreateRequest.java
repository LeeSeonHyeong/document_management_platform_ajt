package com.ajt.backend.domain.inquiry.dto;

import com.ajt.backend.domain.inquiry.InquiryPriority;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;

/**
 * 문의 등록 요청입니다.
 * multipart/form-data로 들어오는 스칼라 값과 첨부 이미지를 검증한 뒤 담아 전달합니다.
 * 입력값 또는 파일 오류는 모두 INVALID_INQUIRY(400)로 통일합니다.
 */
public record InquiryCreateRequest(
        long assigneeId,
        String title,
        String content,
        InquiryPriority priority,
        List<MultipartFile> attachments
) {

    private static final int MAX_FILE_COUNT = 5;
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final long MAX_TOTAL_SIZE = 100L * 1024 * 1024;
    private static final Map<String, Set<String>> ALLOWED_MIME_TYPES = Map.of(
            "png", Set.of("image/png"),
            "jpg", Set.of("image/jpeg"),
            "jpeg", Set.of("image/jpeg")
    );

    public static InquiryCreateRequest of(
            Long assigneeId,
            String title,
            String content,
            String priority,
            List<? extends MultipartFile> attachments
    ) {
        if (assigneeId == null || assigneeId <= 0) {
            throw invalid("담당자를 선택해야 합니다.");
        }
        String safeTitle = requireText(title, "제목을 입력해주세요.");
        if (safeTitle.length() > 200) {
            throw invalid("제목은 200자 이하로 입력해주세요.");
        }
        // TODO(개선): 제목은 200자로 제한하지만 내용(TEXT)에는 상한이 없다.
        //  악의적 대용량 입력을 막도록 내용 길이 상한을 정책으로 확정해 검증을 추가한다.
        String safeContent = requireText(content, "내용을 입력해주세요.");

        InquiryPriority parsedPriority;
        try {
            parsedPriority = InquiryPriority.fromApiValue(priority);
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage());
        }

        List<MultipartFile> copiedFiles = attachments == null ? List.of() : List.copyOf(attachments);
        validateAttachments(copiedFiles);

        return new InquiryCreateRequest(assigneeId, safeTitle, safeContent, parsedPriority, copiedFiles);
    }

    private static void validateAttachments(List<MultipartFile> files) {
        if (files.size() > MAX_FILE_COUNT) {
            throw invalid("첨부 이미지는 최대 5개까지 등록할 수 있습니다.");
        }

        long totalSize = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw invalid("빈 파일은 첨부할 수 없습니다.");
            }
            if (file.getSize() > MAX_FILE_SIZE) {
                throw invalid("첨부 파일당 최대 크기는 20MB입니다.");
            }
            validateImageType(file);
            totalSize += file.getSize();
            if (totalSize > MAX_TOTAL_SIZE) {
                throw invalid("첨부 파일 전체 크기는 최대 100MB입니다.");
            }
        }
    }

    private static void validateImageType(MultipartFile file) {
        String extension = extensionOf(file.getOriginalFilename());
        String contentType = file.getContentType();
        if (extension == null || contentType == null || !ALLOWED_MIME_TYPES
                .getOrDefault(extension, Set.of())
                .contains(contentType.toLowerCase(Locale.ROOT))) {
            throw invalid("첨부 이미지는 PNG, JPG, JPEG만 등록할 수 있습니다.");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw invalid(message);
        }
        return value.trim();
    }

    private static String extensionOf(String originalFileName) {
        if (originalFileName == null) {
            return null;
        }
        int extensionStart = originalFileName.lastIndexOf('.');
        if (extensionStart < 1 || extensionStart == originalFileName.length() - 1) {
            return null;
        }
        return originalFileName.substring(extensionStart + 1).toLowerCase(Locale.ROOT);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_INQUIRY, message);
    }
}
