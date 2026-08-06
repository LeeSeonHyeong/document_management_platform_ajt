package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.api.DocumentUploadRequest;
import com.ajt.backend.domain.document.api.DocumentUploadResponse;
import com.ajt.backend.domain.document.ScopeKey;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.member.DepartmentScopePolicy;
import com.ajt.backend.domain.member.ScopeAccess;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentUploadService {

    private final CurrentMemberProvider currentMemberProvider;
    private final WikiScopeRepository wikiScopeRepository;
    private final DocumentCategoryRepository documentCategoryRepository;
    private final DocumentRepository documentRepository;
    private final DocumentFileStorage fileStorage;
    private final DepartmentScopePolicy departmentScopePolicy;

    public DocumentUploadService(
            CurrentMemberProvider currentMemberProvider,
            WikiScopeRepository wikiScopeRepository,
            DocumentCategoryRepository documentCategoryRepository,
            DocumentRepository documentRepository,
            DocumentFileStorage fileStorage,
            DepartmentScopePolicy departmentScopePolicy
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.wikiScopeRepository = wikiScopeRepository;
        this.documentCategoryRepository = documentCategoryRepository;
        this.documentRepository = documentRepository;
        this.fileStorage = fileStorage;
        this.departmentScopePolicy = departmentScopePolicy;
    }

    @Transactional
    public DocumentUploadResponse upload(DocumentUploadRequest request) {
        CurrentMember currentMember = currentMemberProvider.currentMember();
        if (!currentMember.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        ScopeAccess access = departmentScopePolicy.resolve(currentMember.memberId());
        String scopeKey = resolveScopeKey(request, access);
        // 수정(S15P11B106-199): 부서관리자는 담당 부서 단일 scope로만 업로드할 수 있다.
        //   전체(ALL)·타부서·복수 부서(scope_key "D1-D2") 업로드는 차단한다. 최고관리자는 제한 없음.
        if (!access.canAccessScopeKey(scopeKey)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Long categoryId = null;
        if (request.isClassified()) {
            DocumentCategory category = documentCategoryRepository.findById(request.documentCategoryId())
                    .orElseThrow(this::invalidUpload);
            if (!category.belongsToScope(scopeKey)) {
                throw invalidUpload();
            }
            categoryId = category.id();
        }

        String ensuredScopeKey = scopeKey;
        wikiScopeRepository.findById(scopeKey)
                .orElseGet(() -> wikiScopeRepository.save(wikiScopeFor(ensuredScopeKey)));

        List<String> storedPaths = new ArrayList<>();
        try {
            List<Document> documents =
                    saveDocuments(request, currentMember.memberId(), categoryId, scopeKey, storedPaths);
            List<Long> documentIds = documents.stream().map(Document::id).toList();

            // 업로드는 AI 작업을 만들지 않는다(S15P11B106-276). 카테고리·공개 부서가 확정된 뒤
            // POST /ai-jobs 가 작업을 만들고 바로 시작한다(AiJobCreateService).
            return new DocumentUploadResponse(
                    documentIds.stream().map(String::valueOf).toList(),
                    scopeKey,
                    DocumentStatus.UPLOADED.name().toLowerCase(),
                    Instant.now()
            );
        } catch (RuntimeException exception) {
            deleteStoredFiles(storedPaths);
            throw exception;
        }
    }

    /**
     * 문서를 담을 scope_key를 정합니다.
     *
     * <p>공개 범위가 지정된 요청은 그 범위를 그대로 쓴다. 확정 전 업로드는 파일을 어딘가에는
     * 둬야 하므로(scope_key는 NOT NULL이고 저장 경로에도 들어간다) <b>임시 scope</b>를 쓴다.
     * 부서관리자는 담당 부서 scope 밖으로 나갈 수 없으므로 담당 부서 scope, 최고관리자는
     * 담당 부서가 없으므로 전체(ALL)다. 확정(PATCH)에서 실제 범위로 파일까지 옮겨진다.
     */
    private String resolveScopeKey(DocumentUploadRequest request, ScopeAccess access) {
        if (request.scopeKey() != null) {
            return request.scopeKey().value();
        }
        String managedScopeKey = access.managedScopeKey();
        return managedScopeKey != null ? managedScopeKey : "ALL";
    }

    private List<Document> saveDocuments(
            DocumentUploadRequest request,
            long uploaderId,
            Long documentCategoryId,
            String scopeKey,
            List<String> storedPaths
    ) {
        List<Document> documents = new ArrayList<>();
        for (MultipartFile file : request.files()) {
            Document document = documentRepository.save(Document.uploaded(
                    uploaderId,
                    documentCategoryId,
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

    private WikiScope wikiScopeFor(String scopeKey) {
        ScopeKey parsed = ScopeKey.parse(scopeKey);
        if (parsed.isAll()) {
            return WikiScope.all();
        }
        return WikiScope.department(parsed.departmentIds());
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
