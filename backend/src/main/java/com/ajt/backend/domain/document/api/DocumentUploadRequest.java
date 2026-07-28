package com.ajt.backend.domain.document.api;

import com.ajt.backend.domain.document.ScopeKey;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;

public record DocumentUploadRequest(
        List<MultipartFile> files,
        long documentCategoryId,
        ScopeKey scopeKey
) {

    private static final int MAX_FILE_COUNT = 20;
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final long MAX_TOTAL_SIZE = 100L * 1024 * 1024;
    private static final Map<String, Set<String>> ALLOWED_MIME_TYPES = Map.of(
            "txt", Set.of("text/plain"),
            "md", Set.of("text/markdown", "text/plain"),
            "pdf", Set.of("application/pdf"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    );

    public static DocumentUploadRequest of(
            List<? extends MultipartFile> files,
            Long documentCategoryId,
            String visibilityType,
            Collection<Long> departmentIds
    ) {
        List<MultipartFile> copiedFiles = files == null ? List.of() : List.copyOf(files);
        validateFiles(copiedFiles);

        if (documentCategoryId == null || documentCategoryId <= 0) {
            throw new DocumentUploadValidationException("documentCategoryId", "문서 카테고리를 지정해야 합니다.");
        }

        try {
            return new DocumentUploadRequest(
                    copiedFiles,
                    documentCategoryId,
                    ScopeKey.from(visibilityType, departmentIds)
            );
        } catch (IllegalArgumentException exception) {
            throw new DocumentUploadValidationException("visibilityType", exception.getMessage());
        }
    }

    private static void validateFiles(List<MultipartFile> files) {
        if (files.isEmpty()) {
            throw new DocumentUploadValidationException("파일을 하나 이상 업로드해야 합니다.");
        }
        if (files.size() > MAX_FILE_COUNT) {
            throw new DocumentUploadValidationException("파일은 최대 20개까지 업로드할 수 있습니다.");
        }

        long totalSize = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new DocumentUploadValidationException("빈 파일은 업로드할 수 없습니다.");
            }
            if (file.getSize() > MAX_FILE_SIZE) {
                throw new DocumentUploadValidationException("파일당 최대 크기는 20MB입니다.");
            }
            validateFileType(file);
            totalSize += file.getSize();
            if (totalSize > MAX_TOTAL_SIZE) {
                throw new DocumentUploadValidationException("요청 전체 파일 크기는 최대 100MB입니다.");
            }
        }
    }

    private static void validateFileType(MultipartFile file) {
        String extension = extensionOf(file.getOriginalFilename());
        String contentType = file.getContentType();
        if (extension == null || contentType == null || !ALLOWED_MIME_TYPES
                .getOrDefault(extension, Set.of())
                .contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new DocumentUploadValidationException("TXT, MD, PDF, DOCX 파일만 업로드할 수 있습니다.");
        }
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
}
