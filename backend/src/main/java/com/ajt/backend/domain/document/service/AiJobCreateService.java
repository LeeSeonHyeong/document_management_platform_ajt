package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.api.AiJobCreateResponse;
import com.ajt.backend.domain.document.api.AiJobCreatedJob;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.member.DepartmentScopePolicy;
import com.ajt.backend.domain.member.ScopeAccess;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 확정된 문서들로 AI 작업을 만들어 바로 시작하는 서비스입니다(S15P11B106-276).
 *
 * <p>업로드는 더 이상 작업을 만들지 않는다({@link DocumentUploadService}). 관리자가 파일을 올린 뒤
 * 카테고리·공개 부서를 지정하고 「AI 작업 시작」을 누르면 이 서비스가 작업을 만든다.
 *
 * <p>작업은 scope_key 하나에 묶이므로(AiJob.scopeKey), 공개 범위가 다른 문서가 섞여 오면
 * <b>범위별로 작업을 나눠</b> 만든다. 프론트가 미리 묶어 보내지 않아도 되게 하려는 것이다.
 *
 * <p>카테고리가 없는 문서(확정 전 업로드)는 넣을 수 없다. DB가 NULL을 허용하므로
 * ({@code document.document_category_id}) 이 검증이 유일한 방어선이다.
 */
@Service
public class AiJobCreateService {

    private final CurrentMemberProvider currentMemberProvider;
    private final DocumentRepository documentRepository;
    private final AiJobRepository aiJobRepository;
    private final DocumentParseJobLauncher parseJobLauncher;
    private final DepartmentScopePolicy departmentScopePolicy;

    public AiJobCreateService(
            CurrentMemberProvider currentMemberProvider,
            DocumentRepository documentRepository,
            AiJobRepository aiJobRepository,
            DocumentParseJobLauncher parseJobLauncher,
            DepartmentScopePolicy departmentScopePolicy
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.documentRepository = documentRepository;
        this.aiJobRepository = aiJobRepository;
        this.parseJobLauncher = parseJobLauncher;
        this.departmentScopePolicy = departmentScopePolicy;
    }

    @Transactional
    public AiJobCreateResponse create(List<Long> documentIds) {
        CurrentMember currentMember = currentMemberProvider.currentMember();
        if (!currentMember.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (documentIds == null || documentIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "documentIds는 하나 이상이어야 합니다.");
        }

        List<Long> distinctIds = documentIds.stream().distinct().toList();
        List<Document> documents = documentRepository.findAllById(distinctIds);
        if (documents.size() != distinctIds.size()) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        ScopeAccess access = departmentScopePolicy.resolve(currentMember.memberId());
        Map<String, List<Long>> idsByScope = new LinkedHashMap<>();
        for (Document document : documents) {
            validate(document, access);
            idsByScope.computeIfAbsent(document.scopeKey(), key -> new ArrayList<>()).add(document.id());
        }

        List<AiJobCreatedJob> createdJobs = new ArrayList<>();
        List<AiJob> startedJobs = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : idsByScope.entrySet()) {
            String scopeKey = entry.getKey();
            AiJob job = aiJobRepository.save(AiJob.waiting(
                    currentMember.memberId(),
                    scopeKey,
                    scopeKey + "/jobs/" + UUID.randomUUID(),
                    entry.getValue()
            ));
            job.start();
            aiJobRepository.save(job);
            startedJobs.add(job);
            createdJobs.add(new AiJobCreatedJob(
                    String.valueOf(job.id()),
                    scopeKey,
                    job.status().name().toLowerCase(),
                    entry.getValue().stream().map(String::valueOf).toList()
            ));
        }

        // 커밋 후에 워커가 뜬다(AsyncDocumentParseJobLauncher). 워커는 PROCESSING 상태를 보고
        // 다시 start()하지 않는다 — AiJobStartService와 같은 방식이다.
        for (AiJob job : startedJobs) {
            parseJobLauncher.launch(job, DocumentReprocessPlan.added());
        }

        return new AiJobCreateResponse(createdJobs);
    }

    private void validate(Document document, ScopeAccess access) {
        // 부서관리자는 담당 부서 scope 문서만 다룰 수 있다. 담당 밖이면 존재를 숨겨 404다
        // (DocumentManagementService와 같은 규칙).
        if (!access.canAccessScopeKey(document.scopeKey())) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        if (!document.isClassified()) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "카테고리가 지정되지 않은 문서는 AI 작업을 시작할 수 없습니다.");
        }
        // 이미 처리 중이거나 끝난 문서는 다시 넣지 않는다. 재처리는 retry·PATCH 경로가 담당한다.
        if (document.status() != DocumentStatus.UPLOADED) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
    }
}
