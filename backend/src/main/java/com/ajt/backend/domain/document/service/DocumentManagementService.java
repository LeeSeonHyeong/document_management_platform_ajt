package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.api.DocumentDetailResponse;
import com.ajt.backend.domain.document.api.DocumentRetryResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentManagementService {

    private static final Logger log = LoggerFactory.getLogger(DocumentManagementService.class);

    private final CurrentMemberProvider currentMemberProvider;
    private final DocumentRepository documentRepository;
    private final DocumentCategoryRepository documentCategoryRepository;
    private final AiJobRepository aiJobRepository;
    private final DocumentParseJobLauncher parseJobLauncher;
    // 작업: 파일 다운로드용 저장소. 저장된 원본 파일을 Resource로 읽는다.
    private final DocumentFileStorage documentFileStorage;

    public DocumentManagementService(
            CurrentMemberProvider currentMemberProvider,
            DocumentRepository documentRepository,
            DocumentCategoryRepository documentCategoryRepository,
            AiJobRepository aiJobRepository,
            DocumentParseJobLauncher parseJobLauncher,
            DocumentFileStorage documentFileStorage
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.documentRepository = documentRepository;
        this.documentCategoryRepository = documentCategoryRepository;
        this.aiJobRepository = aiJobRepository;
        this.parseJobLauncher = parseJobLauncher;
        this.documentFileStorage = documentFileStorage;
    }

    @Transactional(readOnly = true)
    public DocumentDetailResponse getDocument(long documentId) {
        requireAdmin();
        Document document = findDocument(documentId);
        DocumentCategory category = documentCategoryRepository.findById(document.documentCategoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        return new DocumentDetailResponse(
                String.valueOf(document.id()),
                document.originalFileName(),
                document.scopeKey(),
                new DocumentDetailResponse.CategoryResponse(
                        String.valueOf(category.id()),
                        category.name()
                ),
                document.status().name().toLowerCase(),
                document.failureReason(),
                "/api/v1/documents/%d/file".formatted(document.id()),
                List.of(),
                document.createdAt(),
                document.updatedAt()
        );
    }

    /**
     * 작업(DOC-06, FR-DOC-016): 원본문서 파일 다운로드.
     * 상세 조회와 동일하게 관리자만 허용한다(사원 scope 접근은 문서 목록 작업에서 추가 예정).
     * 저장소에서 파일을 읽어 원래 파일명·MIME으로 반환하며, 실제 저장 경로는 응답에 노출하지 않는다.
     */
    @Transactional(readOnly = true)
    public DocumentFileDownload downloadFile(long documentId) {
        requireAdmin();
        Document document = findDocument(documentId);
        Resource resource = documentFileStorage.load(document.originalPath());
        // 작업: DB엔 경로가 있으나 실제 파일이 없으면(유실) 500 대신 404로 안전하게 처리한다.
        if (!resource.isReadable()) {
            log.warn("원본문서 파일 유실로 다운로드 불가: documentId={}", document.id());
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return new DocumentFileDownload(resource, document.originalFileName(), document.mimeType());
    }

    @Transactional
    public DocumentRetryResponse retry(long documentId) {
        CurrentMember currentMember = requireAdmin();
        Document document = findDocument(documentId);
        try {
            document.retryParsing();
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.INVALID_DOCUMENT_STATUS, exception.getMessage());
        }

        AiJob job = aiJobRepository.save(AiJob.waiting(
                currentMember.memberId(),
                document.scopeKey(),
                document.scopeKey() + "/jobs/" + UUID.randomUUID(),
                List.of(document.id())
        ));
        parseJobLauncher.launch(job);

        return new DocumentRetryResponse(
                String.valueOf(job.id()),
                String.valueOf(document.id()),
                job.status().name().toLowerCase(),
                LocalDateTime.now()
        );
    }

    private CurrentMember requireAdmin() {
        CurrentMember currentMember = currentMemberProvider.currentMember();
        if (!currentMember.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return currentMember;
    }

    private Document findDocument(long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
