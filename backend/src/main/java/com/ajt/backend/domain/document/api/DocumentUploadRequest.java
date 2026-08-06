package com.ajt.backend.domain.document.api;

import com.ajt.backend.domain.document.ScopeKey;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;

/**
 * 원본문서 업로드 요청입니다.
 *
 * <p>카테고리·공개 범위는 <b>선택</b>이다(S15P11B106-276). 관리자가 파일을 고르는 즉시 업로드하고
 * 분류는 그 뒤에 지정하므로, 둘 다 비어 있는 요청이 정상이다. 이때 {@code documentCategoryId}와
 * {@code scopeKey}는 null이고, 확정은 {@code PATCH /documents/{id}}가 담당한다.
 * 파일 자체의 검증(개수·용량·형식)은 분류 여부와 무관하게 항상 수행한다.
 */
public record DocumentUploadRequest(
        List<MultipartFile> files,
        Long documentCategoryId,
        ScopeKey scopeKey
) {

    /** 카테고리·공개 범위가 함께 지정된 요청인지. 둘 중 하나만 온 요청은 of()에서 거부된다. */
    public boolean isClassified() {
        return documentCategoryId != null && scopeKey != null;
    }

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

        boolean hasCategory = documentCategoryId != null;
        boolean hasVisibility = visibilityType != null && !visibilityType.isBlank();

        // 둘 다 없으면 확정 전 업로드다. 하나만 있으면 의도를 알 수 없어 거부한다 —
        // 카테고리는 공개 범위에 속하므로(category.belongsToScope) 반쪽만으로는 검증할 수 없다.
        if (!hasCategory && !hasVisibility) {
            return new DocumentUploadRequest(copiedFiles, null, null);
        }
        if (!hasCategory) {
            throw new DocumentUploadValidationException("documentCategoryId", "문서 카테고리를 지정해야 합니다.");
        }
        if (!hasVisibility) {
            throw new DocumentUploadValidationException("visibilityType", "공개 범위를 지정해야 합니다.");
        }
        if (documentCategoryId <= 0) {
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
            validateSingleFile(file);
            totalSize += file.getSize();
            if (totalSize > MAX_TOTAL_SIZE) {
                throw new DocumentUploadValidationException("요청 전체 파일 크기는 최대 100MB입니다.");
            }
        }
    }

    /**
     * 파일 교체(PUT /documents/{id}/file)용 단일 파일 검증.
     * 업로드와 동일한 형식·용량 규칙(빈 파일 불가, 20MB 이하, TXT/MD/PDF/DOCX)을 재사용한다.
     */
    public static void validateReplacementFile(MultipartFile file) {
        validateSingleFile(file);
    }

    private static void validateSingleFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentUploadValidationException("빈 파일은 업로드할 수 없습니다.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new DocumentUploadValidationException("파일당 최대 크기는 20MB입니다.");
        }
        validateFileType(file);
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
