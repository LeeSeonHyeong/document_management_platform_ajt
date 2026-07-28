package com.ajt.backend.domain.document.api;

import com.ajt.backend.domain.document.service.DocumentUploadService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class DocumentUploadController {

    private final DocumentUploadService documentUploadService;

    public DocumentUploadController(DocumentUploadService documentUploadService) {
        this.documentUploadService = documentUploadService;
    }

    @PostMapping("/api/v1/documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentUploadResponse upload(
            @RequestParam(name = "files", required = false) List<MultipartFile> files,
            @RequestParam(name = "documentCategoryId", required = false) Long documentCategoryId,
            @RequestParam(name = "visibilityType", required = false) String visibilityType,
            @RequestParam(name = "departmentIds", required = false) List<Long> departmentIds
    ) {
        DocumentUploadRequest request = DocumentUploadRequest.of(
                files,
                documentCategoryId,
                visibilityType,
                departmentIds == null ? List.of() : departmentIds
        );
        return documentUploadService.upload(request);
    }
}
