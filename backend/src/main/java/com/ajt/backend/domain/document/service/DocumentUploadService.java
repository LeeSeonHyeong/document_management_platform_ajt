package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.api.DocumentUploadRequest;
import com.ajt.backend.domain.document.api.DocumentUploadResponse;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.member.DepartmentScopePolicy;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentUploadService {

    private final CurrentMemberProvider currentMemberProvider;
    private final WikiScopeRepository wikiScopeRepository;
    private final DocumentCategoryRepository documentCategoryRepository;
    private final DocumentRepository documentRepository;
    private final AiJobRepository aiJobRepository;
    private final DocumentFileStorage fileStorage;
    private final DepartmentScopePolicy departmentScopePolicy;

    public DocumentUploadService(
            CurrentMemberProvider currentMemberProvider,
            WikiScopeRepository wikiScopeRepository,
            DocumentCategoryRepository documentCategoryRepository,
            DocumentRepository documentRepository,
            AiJobRepository aiJobRepository,
            DocumentFileStorage fileStorage,
            DepartmentScopePolicy departmentScopePolicy
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.wikiScopeRepository = wikiScopeRepository;
        this.documentCategoryRepository = documentCategoryRepository;
        this.documentRepository = documentRepository;
        this.aiJobRepository = aiJobRepository;
        this.fileStorage = fileStorage;
        this.departmentScopePolicy = departmentScopePolicy;
    }

    @Transactional
    public DocumentUploadResponse upload(DocumentUploadRequest request) {
        CurrentMember currentMember = currentMemberProvider.currentMember();
        if (!currentMember.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        String scopeKey = request.scopeKey().value();
        // 수정(S15P11B106-199): 부서관리자는 담당 부서 단일 scope로만 업로드할 수 있다.
        //   전체(ALL)·타부서·복수 부서(scope_key "D1-D2") 업로드는 차단한다. 최고관리자는 제한 없음.
        if (!departmentScopePolicy.resolve(currentMember.memberId()).canAccessScopeKey(scopeKey)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        DocumentCategory category = documentCategoryRepository.findById(request.documentCategoryId())
                .orElseThrow(this::invalidUpload);
        if (!category.belongsToScope(scopeKey)) {
            throw invalidUpload();
        }

        wikiScopeRepository.findById(scopeKey)
                .orElseGet(() -> wikiScopeRepository.save(wikiScopeFor(request)));

        List<String> storedPaths = new ArrayList<>();
        try {
            List<Document> documents = saveDocuments(request, currentMember.memberId(), scopeKey, storedPaths);
            List<Long> documentIds = documents.stream().map(Document::id).toList();
            // 업로드는 작업을 WAITING으로만 만든다. 실제 파싱·Wiki 변환은 관리자가 대기 화면에서
            // 문서별 공개 범위를 확정한 뒤 POST /ai-jobs/{jobId}/start 로 시작한다(AiJobStartService).
            AiJob job = aiJobRepository.save(AiJob.waiting(
                    currentMember.memberId(),
                    scopeKey,
                    scopeKey + "/jobs/" + UUID.randomUUID(),
                    documentIds
            ));

            return new DocumentUploadResponse(
                    String.valueOf(job.id()),
                    documentIds.stream().map(String::valueOf).toList(),
                    scopeKey,
                    job.status().name().toLowerCase(),
                    LocalDateTime.now()
            );
        } catch (RuntimeException exception) {
            deleteStoredFiles(storedPaths);
            throw exception;
        }
    }

    private List<Document> saveDocuments(
            DocumentUploadRequest request,
            long uploaderId,
            String scopeKey,
            List<String> storedPaths
    ) {
        List<Document> documents = new ArrayList<>();
        for (MultipartFile file : request.files()) {
            Document document = documentRepository.save(Document.uploaded(
                    uploaderId,
                    request.documentCategoryId(),
                    scopeKey,
                    file.getOriginalFilename(),
                    "pending",
                    file.getContentType(),
                    file.getSize()
            ));
            String storedPath = storeOriginal(scopeKey, document.id(), file);
            storedPaths.add(storedPath);
            document.changeOriginalPath(storedPath);
            documents.add(document);
        }
        return documents;
    }

    private WikiScope wikiScopeFor(DocumentUploadRequest request) {
        if ("ALL".equals(request.scopeKey().value())) {
            return WikiScope.all();
        }
        return WikiScope.department(request.scopeKey().departmentIds());
    }

    private String storeOriginal(String scopeKey, long documentId, MultipartFile file) {
        try {
            return fileStorage.storeOriginal(scopeKey, documentId, file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void deleteStoredFiles(List<String> storedPaths) {
        List<String> reversedPaths = new ArrayList<>(storedPaths);
        Collections.reverse(reversedPaths);
        for (String storedPath : reversedPaths) {
            try {
                fileStorage.delete(storedPath);
            } catch (IOException ignored) {
            }
        }
    }

    private BusinessException invalidUpload() {
        return new BusinessException(ErrorCode.INVALID_DOCUMENT_UPLOAD);
    }
}
