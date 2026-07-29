package com.ajt.backend.domain.inquiry.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public class LocalInquiryFileStorage implements InquiryFileStorage {

    private final Path storageRoot;

    public LocalInquiryFileStorage(Path storageRoot) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    @Override
    public String storeAttachment(long inquiryId, String attachmentId, MultipartFile file) throws IOException {
        String extension = extensionOf(file.getOriginalFilename());
        String fileName = extension == null ? attachmentId : attachmentId + "." + extension;
        String storedPath = "inquiries/" + inquiryId + "/attachments/" + fileName;
        Path target = resolve(storedPath);
        Files.createDirectories(target.getParent());
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return storedPath;
    }

    @Override
    public Resource load(String storedPath) {
        return new FileSystemResource(resolve(storedPath));
    }

    @Override
    public void delete(String storedPath) throws IOException {
        Files.deleteIfExists(resolve(storedPath));
    }

    private Path resolve(String storedPath) {
        Path path = storageRoot.resolve(storedPath).normalize();
        if (!path.startsWith(storageRoot)) {
            throw new IllegalArgumentException("저장 경로가 유효하지 않습니다.");
        }
        return path;
    }

    private String extensionOf(String originalFileName) {
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
