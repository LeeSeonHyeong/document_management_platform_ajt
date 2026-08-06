package com.ajt.backend.domain.document.api;

import com.ajt.backend.domain.document.service.DocumentFileDownload;
import com.ajt.backend.domain.document.service.DocumentUploadService;
import com.ajt.backend.domain.document.service.DocumentManagementService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class DocumentUploadController {

    private final DocumentUploadService documentUploadService;
    private final DocumentManagementService documentManagementService;

    public DocumentUploadController(
            DocumentUploadService documentUploadService,
            DocumentManagementService documentManagementService
    ) {
        this.documentUploadService = documentUploadService;
        this.documentManagementService = documentManagementService;
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

    // 작업(DOC-03/DOC-04): 원본문서 목록. 역할별 노출 범위·필터·페이지네이션은 서비스가 담당한다.
    @GetMapping("/api/v1/documents")
    public DocumentListResponse listDocuments(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "scopeKey", required = false) String scopeKey,
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "fileType", required = false) String fileType,
            @RequestParam(name = "departmentId", required = false) Long departmentId,
            @RequestParam(name = "uploadedFrom", required = false) String uploadedFrom,
            @RequestParam(name = "uploadedTo", required = false) String uploadedTo,
            // 분류 완료 여부(S15P11B106-276). true면 카테고리가 지정된 문서만, false면 확정 전 문서만.
            @RequestParam(name = "classified", required = false) Boolean classified,
            @RequestParam(name = "sort", required = false) String sort
    ) {
        return documentManagementService.findDocuments(
                page, size, scopeKey, categoryId, status, keyword, fileType, departmentId,
                uploadedFrom, uploadedTo, classified, sort);
    }

    @GetMapping("/api/v1/documents/{documentId}")
    public DocumentDetailResponse getDocument(@PathVariable long documentId) {
        return documentManagementService.getDocument(documentId);
    }

    // 작업(DOC-06): 원본문서 파일 다운로드. 권한 검사는 서비스가 하고, 여기선 파일명·타입 헤더만 구성한다.
    @GetMapping("/api/v1/documents/{documentId}/file")
    public ResponseEntity<Resource> downloadFile(@PathVariable long documentId) {
        DocumentFileDownload download = documentManagementService.downloadFile(documentId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.contentType());
        } catch (RuntimeException exception) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(download.resource());
    }

    @PostMapping("/api/v1/documents/{documentId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentRetryResponse retry(@PathVariable long documentId) {
        return documentManagementService.retry(documentId);
    }

    // 작업(DOC): 원본문서 파일 교체. 문서 ID를 유지한 채 원본 파일만 새 파일로 바꾸고 재처리한다(202 + jobId).
    @PutMapping("/api/v1/documents/{documentId}/file")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentFileReplaceResponse replaceFile(
            @PathVariable long documentId,
            @RequestParam(name = "file", required = false) MultipartFile file
    ) {
        return documentManagementService.replaceFile(documentId, file);
    }

    // 작업(DOC-05): 문서 메타데이터(카테고리·공개범위) 수정 → 재처리.
    @PatchMapping("/api/v1/documents/{documentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentUpdateResponse update(
            @PathVariable long documentId,
            @RequestBody DocumentMetadataUpdateRequest request
    ) {
        return documentManagementService.update(documentId, request);
    }

    // 작업(DOC-05): 문서 하드 삭제 → 해당 범위 Wiki 재처리.
    @DeleteMapping("/api/v1/documents/{documentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentDeleteResponse delete(@PathVariable long documentId) {
        return documentManagementService.delete(documentId);
    }
}
