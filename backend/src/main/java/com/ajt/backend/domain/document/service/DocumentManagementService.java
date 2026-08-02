package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.document.ScopeKey;
import com.ajt.backend.domain.document.api.DocumentDeleteResponse;
import com.ajt.backend.domain.document.api.DocumentDepartmentResponse;
import com.ajt.backend.domain.document.api.DocumentUploaderResponse;
import com.ajt.backend.domain.document.api.DocumentDetailResponse;
import com.ajt.backend.domain.document.api.DocumentFileReplaceResponse;
import com.ajt.backend.domain.document.api.DocumentListResponse;
import com.ajt.backend.domain.document.api.DocumentMetadataUpdateRequest;
import com.ajt.backend.domain.document.api.DocumentRetryResponse;
import com.ajt.backend.domain.document.api.DocumentSummaryResponse;
import com.ajt.backend.domain.document.api.DocumentUpdateResponse;
import com.ajt.backend.domain.document.api.DocumentUploadRequest;
import com.ajt.backend.domain.document.model.AiJob;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.DocumentStatus;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.model.WikiScopeVisibilityType;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.document.storage.DocumentFileMutation;
import com.ajt.backend.domain.document.storage.StagedOriginalFile;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

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
    // 작업(DOC-03): 사원 접근권한 판정용. 소속 부서와 접근 가능한 공개범위(scopeKey)를 구한다.
    private final MemberRepository memberRepository;
    private final WikiScopeRepository wikiScopeRepository;
    // 작업(S15P11B106-70): 목록·상세 응답에 공개범위 부서명을 채우기 위한 부서 조회.
    private final DepartmentRepository departmentRepository;
    // 수정(S15P11B106-146): 파일 교체 확정(promote)이 커밋 후 실패하면 작업/문서를 FAILED로 남기기 위한 컴포넌트.
    private final AiJobFailureMarker aiJobFailureMarker;
    private final DocumentFailureMarker documentFailureMarker;

    public DocumentManagementService(
            CurrentMemberProvider currentMemberProvider,
            DocumentRepository documentRepository,
            DocumentCategoryRepository documentCategoryRepository,
            AiJobRepository aiJobRepository,
            DocumentParseJobLauncher parseJobLauncher,
            DocumentFileStorage documentFileStorage,
            MemberRepository memberRepository,
            WikiScopeRepository wikiScopeRepository,
            DepartmentRepository departmentRepository,
            AiJobFailureMarker aiJobFailureMarker,
            DocumentFailureMarker documentFailureMarker
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.documentRepository = documentRepository;
        this.documentCategoryRepository = documentCategoryRepository;
        this.aiJobRepository = aiJobRepository;
        this.parseJobLauncher = parseJobLauncher;
        this.documentFileStorage = documentFileStorage;
        this.memberRepository = memberRepository;
        this.wikiScopeRepository = wikiScopeRepository;
        this.departmentRepository = departmentRepository;
        this.aiJobFailureMarker = aiJobFailureMarker;
        this.documentFailureMarker = documentFailureMarker;
    }

    @Transactional(readOnly = true)
    public DocumentDetailResponse getDocument(long documentId) {
        requireAdmin();
        Document document = findDocument(documentId);
        DocumentCategory category = documentCategoryRepository.findById(document.documentCategoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        return toDetail(document, category);
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
        // 수정(S15P11B106-146): 파일 교체 확정(promote) 실패 등으로 FAILED가 된 문서는 DB 메타데이터와 실제 파일이
        //   어긋날 수 있으므로(같은 확장자 교체 실패 시 기존 파일이 새 파일처럼 내려갈 수 있음) 다운로드를 막는다.
        if (document.status() == DocumentStatus.FAILED) {
            throw new BusinessException(ErrorCode.INVALID_DOCUMENT_STATUS);
        }
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
        parseJobLauncher.launch(job, DocumentReprocessPlan.added());

        return new DocumentRetryResponse(
                String.valueOf(job.id()),
                String.valueOf(document.id()),
                job.status().name().toLowerCase(),
                LocalDateTime.now()
        );
    }

    /**
     * 작업(DOC): 원본문서 파일 교체 (PUT /documents/{id}/file).
     * 문서 ID·카테고리·공개범위는 유지한 채 원본 파일만 새 파일로 교체하고, 해당 문서를 재처리한다(202 + jobId).
     *
     * <p>수정(S15P11B106-146): 새 파일을 최종 경로에 바로 덮어쓰지 않고 staging 경로에 저장한다. DB 트랜잭션에서는
     * 메타데이터 변경과 재처리 작업 생성만 하고, 커밋이 성공한 뒤에만 staging→최종 이동으로 교체를 확정하며 기존
     * 원본·파싱 파일을 지운다. 롤백되면 staging 파일만 정리하고 기존 파일은 그대로 둔다(문서 삭제 afterCommit 패턴과 동일).
     */
    @Transactional
    public DocumentFileReplaceResponse replaceFile(long documentId, MultipartFile file) {
        CurrentMember admin = requireAdmin();
        Document document = findDocument(documentId);
        DocumentUploadRequest.validateReplacementFile(file);
        ensureNotInProgress(document);

        // 새 파일을 저장하기 전에 교체 전 파싱 본문을 읽어 둔다. 커밋 후 parsed.md도 정리되고 재처리로 새로
        // 생성되므로, 옛 내용을 근거로 쓴 문단·각주를 고치도록 지시하려면 지금 읽어 재처리 계획에 담아야 한다.
        String removedParsedMarkdown = readParsedMarkdownQuietly(document);
        String previousOriginalPath = document.originalPath();
        String previousParsedPath = document.parsedPath();

        StagedOriginalFile staged;
        try {
            // 최종 경로가 아닌 staging에 저장한다(기존 파일을 아직 건드리지 않는다).
            staged = documentFileStorage.stageOriginal(document.scopeKey(), document.id(), file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        try {
            // 메타데이터는 최종 경로를 가리키게 한다. 실제 파일은 커밋 후에 그 경로로 확정된다.
            document.replaceFile(file.getOriginalFilename(), staged.finalPath(), file.getContentType(), file.getSize());

            // 재처리 작업을 waiting 상태로 만들되, 이 자리에서 실행하지는 않는다. 재처리 범위는 이 문서 1건이다.
            // 교체 전 본문이 없으면 계획이 document_added로 강등된다(DocumentReprocessPlan.replaced 주석 참고).
            document.markForReprocess();
            DocumentReprocessPlan plan = DocumentReprocessPlan.replaced(document.id(), removedParsedMarkdown);
            AiJob job = aiJobRepository.save(AiJob.waiting(
                    admin.memberId(),
                    document.scopeKey(),
                    document.scopeKey() + "/jobs/" + UUID.randomUUID(),
                    List.of(document.id())
            ));

            // 파일 확정 → (성공 시) 기존 파일 삭제 + 재처리 실행 순서를 하나의 afterCommit 흐름에서 보장한다.
            // promote가 실패하면 재처리를 시작하지 않고 작업을 FAILED로 남긴다(DB↔실제 파일 불일치를 조용히 묻지 않는다).
            registerAfterCommitFileReplace(
                    document.id(), staged.stagingPath(), staged.finalPath(),
                    previousOriginalPath, previousParsedPath, job, plan);

            return new DocumentFileReplaceResponse(
                    String.valueOf(job.id()),
                    String.valueOf(document.id()),
                    job.status().name().toLowerCase()
            );
        } catch (RuntimeException exception) {
            // DB 작업(메타 변경·작업 생성)이 실패하면 트랜잭션이 롤백된다. 새로 저장한 staging 임시 파일만 정리하고
            // 기존 파일은 손대지 않는다(등록 전 실패라 afterCompletion이 실행되지 않으므로 여기서 직접 정리).
            deleteQuietly(staged.stagingPath());
            throw exception;
        }
    }

    /**
     * 수정(S15P11B106-146): 파일 교체 확정을 트랜잭션 커밋 이후로 미룬다.
     *
     * <p>afterCommit에서 staging→최종 이동(promote)을 먼저 하고, 성공한 경우에만 기존 원본·파싱 파일을 지우고
     * 재처리를 실행한다. promote가 실패하면(=DB↔실제 파일 불일치) 재처리를 시작하지 않고 작업을 FAILED로 남긴다.
     * 롤백(afterCompletion STATUS_ROLLED_BACK)에서는 staging 임시 파일만 정리하고 기존 파일은 그대로 둔다.
     * 동기화가 비활성(트랜잭션 밖)일 때만 즉시 확정한다. replaceFile은 {@code @Transactional}이라 보통 afterCommit 경로를 탄다.
     */
    private void registerAfterCommitFileReplace(
            long documentId, String stagingPath, String finalPath,
            String previousOriginalPath, String previousParsedPath, AiJob job, DocumentReprocessPlan plan) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            finalizeReplace(documentId, stagingPath, finalPath, previousOriginalPath, previousParsedPath, job, plan);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                finalizeReplace(documentId, stagingPath, finalPath, previousOriginalPath, previousParsedPath, job, plan);
            }

            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    // 롤백: 새로 저장한 임시 파일만 지운다. 기존 파일은 손대지 않았으므로 그대로 유지된다.
                    deleteQuietly(stagingPath);
                }
            }
        });
    }

    /**
     * 커밋 이후 파일 교체를 확정한다. promote 성공 시에만 기존 파일 삭제 + 재처리 실행, 실패 시 작업을 FAILED로 남긴다.
     */
    private void finalizeReplace(
            long documentId, String stagingPath, String finalPath,
            String previousOriginalPath, String previousParsedPath, AiJob job, DocumentReprocessPlan plan) {
        try {
            documentFileStorage.promoteStagedOriginal(stagingPath, finalPath);
        } catch (IOException exception) {
            // promote 실패는 DB↔실제 파일 불일치라 삭제 실패보다 강하게 처리한다: 재처리를 시작하지 않고,
            // 작업과 문서를 모두 FAILED로 남긴다(문서만 정상인 것처럼 보이지 않게 한다).
            log.error("교체 파일 확정 실패(staging→최종 이동). 재처리를 시작하지 않고 작업·문서를 FAILED로 표시합니다. "
                            + "documentId={}, jobId={}, staging={}, final={}",
                    documentId, job.id(), stagingPath, finalPath, exception);
            // afterCommit 이후 보정이므로 한쪽 마킹이 실패해도 다른 쪽은 최대한 시도한다(각각 독립 try/catch).
            // 어떤 마킹 실패도 사용자 응답을 깨지 않도록 밖으로 던지지 않는다.
            try {
                aiJobFailureMarker.markFailedBeforeStart(
                        job.id(), "원본 파일 확정(staging→최종 이동) 실패로 재처리를 시작하지 못했습니다.");
            } catch (RuntimeException markException) {
                log.error("AiJob 실패 마킹 중 오류. documentId={}, jobId={}", documentId, job.id(), markException);
            }
            try {
                documentFailureMarker.markFailed(
                        documentId, "파일 교체 확정(staging→최종 이동) 실패로 문서 처리에 실패했습니다.");
            } catch (RuntimeException markException) {
                log.error("문서 실패 마킹 중 오류. documentId={}", documentId, markException);
            }
            return;
        }
        // promote 성공: 기존 파일 정리(실패는 로그만) 후 재처리 실행
        deleteReplacedOldFiles(finalPath, previousOriginalPath, previousParsedPath);
        parseJobLauncher.launchNow(job, plan);
    }

    private void deleteReplacedOldFiles(String finalPath, String previousOriginalPath, String previousParsedPath) {
        // 확장자가 바뀌어 경로가 달라진 경우에만 기존 원본을 지운다(같은 경로면 promote가 이미 덮어썼다).
        if (previousOriginalPath != null && !previousOriginalPath.equals(finalPath)) {
            deleteQuietly(previousOriginalPath);
        }
        // 기존 파싱 파일은 옛 내용 기준이라 정리한다. 재처리가 새 parsed.md를 다시 만든다. null이면 무시된다.
        deleteQuietly(previousParsedPath);
    }

    /**
     * 작업(DOC-05): 원본문서 메타데이터(카테고리·공개범위) 수정.
     * 공개범위가 바뀌면 기존·새 범위 Wiki를 각각 현재 문서 기준으로 재처리한다(202 + 재처리 jobId + 수정된 문서).
     */
    @Transactional
    public DocumentUpdateResponse update(long documentId, DocumentMetadataUpdateRequest request) {
        CurrentMember admin = requireAdmin();
        Document document = findDocument(documentId);
        ensureNotInProgress(document);

        if (request.documentCategoryId() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "documentCategoryId는 필수입니다.");
        }
        ScopeKey newScope;
        try {
            newScope = ScopeKey.from(request.visibilityType(), request.departmentIds());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, exception.getMessage());
        }
        String newScopeKey = newScope.value();

        // 리뷰(#4): 새 카테고리가 없으면 404로 처리한다(계약의 400 "카테고리·범위 조합 오류"로 볼 여지도 있으나 방어적으로 404).
        DocumentCategory category = documentCategoryRepository.findById(request.documentCategoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_CATEGORY_NOT_FOUND));
        if (!category.belongsToScope(newScopeKey)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "카테고리와 공개 범위가 일치하지 않습니다.");
        }

        String oldScopeKey = document.scopeKey();
        boolean scopeChanged = !oldScopeKey.equals(newScopeKey);
        ensureWikiScope(newScope);
        if (scopeChanged) {
            // 양쪽 범위 중 하나라도 처리 중 문서가 있으면 같은 공간의 Wiki를 동시에 고치게 되므로 충돌로 막는다.
            ensureScopeNotProcessing(oldScopeKey);
            ensureScopeNotProcessing(newScopeKey);
        }

        if (!scopeChanged) {
            document.changeCategoryAndScope(request.documentCategoryId(), newScopeKey);
            AiJob job = reprocessDocument(admin.memberId(), document);
            return new DocumentUpdateResponse(String.valueOf(job.id()), job.status().name().toLowerCase(), List.of(), toDetail(document, category));
        }
        // 파일을 옮기기 전에 옛 파싱 본문을 읽어 둔다. 걷어내기 요청의 removedParsedMarkdown은
        // 계약의 필수 값인데, 이동 뒤에는 옛 경로에서 읽을 수 없다.
        String removedParsedMarkdown = readParsedMarkdownQuietly(document);
        try {
            DocumentFileMutation fileMutation = documentFileStorage.moveToScope(
                    document.originalPath(), document.parsedPath(), newScopeKey, document.id());
            try {
                document.changeCategoryScopeAndPaths(request.documentCategoryId(), newScopeKey, fileMutation.originalPath(), fileMutation.parsedPath());
                AiJob oldJob = removeDocumentFromScope(
                        admin.memberId(), document, oldScopeKey, removedParsedMarkdown);
                AiJob newJob = reprocessDocument(admin.memberId(), document);
                registerFileRollback(fileMutation);
                List<com.ajt.backend.domain.document.api.ReprocessJobResponse> reprocessJobs = new ArrayList<>();
                if (oldJob != null) {
                    reprocessJobs.add(new com.ajt.backend.domain.document.api.ReprocessJobResponse(
                            oldScopeKey, String.valueOf(oldJob.id())));
                }
                reprocessJobs.add(new com.ajt.backend.domain.document.api.ReprocessJobResponse(
                        newScopeKey, String.valueOf(newJob.id())));
                return new DocumentUpdateResponse(
                        String.valueOf(newJob.id()), newJob.status().name().toLowerCase(),
                        reprocessJobs,
                        toDetail(document, category));
            } catch (RuntimeException exception) {
                rollbackFileMutation(fileMutation, exception);
                throw exception;
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 옛 범위에서 이 문서를 걷어내는 재처리 작업을 만듭니다. (FR-DOC-008)
     *
     * <p>옛 파싱 본문이 없으면 계약의 {@code removedParsedMarkdown}을 채울 수 없어 작업을 만들지
     * 않고 {@code null}을 돌려준다. 이 경우 옛 범위 Wiki에 이 문서를 근거로 쓴 서술이 남을 수
     * 있으므로 경고를 남긴다. 아직 파싱되지 않은 문서이거나 파싱 파일이 유실된 경우다.
     */
    private AiJob removeDocumentFromScope(
            long requesterId,
            Document document,
            String oldScopeKey,
            String removedParsedMarkdown
    ) {
        if (removedParsedMarkdown == null || removedParsedMarkdown.isBlank()) {
            log.warn("옛 파싱 본문이 없어 이전 범위 걷어내기를 건너뜁니다: documentId={}, scopeKey={}",
                    document.id(), oldScopeKey);
            return null;
        }
        AiJob job = aiJobRepository.save(AiJob.waiting(
                requesterId,
                oldScopeKey,
                oldScopeKey + "/jobs/" + UUID.randomUUID(),
                List.of(document.id())
        ));
        parseJobLauncher.launch(job, DocumentReprocessPlan.removed(document.id(), removedParsedMarkdown));
        return job;
    }

    /**
     * 하드 삭제된 문서를 이 범위 Wiki에서 걷어내는 작업을 만듭니다. (DR-014)
     *
     * <p>문서 행이 이미 지워졌으므로 작업은 문서 ID만 들고 돈다. 워커의 걷어내기 경로는 문서
     * 엔티티를 읽지 않는다.
     *
     * <p>파싱 본문이 없으면 걷어낼 근거가 없어 작업을 만들지 않고 {@code null}을 돌려준다.
     * 파싱 전이거나 파싱에 실패한 문서는 Wiki에 반영된 적이 없다.
     *
     * <p>수정(S15P11B106-93): 이 {@code null}("재처리할 것이 없는 삭제")은 delete()에서
     * {@code reprocessRequired=false}·{@code jobId=null}·{@code status=skipped}로 응답에 담긴다.
     * 프론트는 {@code reprocessRequired=true}이고 {@code jobId}가 있을 때만 AI 작업 상태를 조회한다.
     */
    private AiJob removeDeletedDocumentFromScope(
            long requesterId,
            long documentId,
            String scopeKey,
            String removedParsedMarkdown
    ) {
        if (removedParsedMarkdown == null || removedParsedMarkdown.isBlank()) {
            log.info("파싱 본문이 없어 Wiki 걷어내기를 건너뜁니다: documentId={}, scopeKey={}",
                    documentId, scopeKey);
            return null;
        }
        AiJob job = aiJobRepository.save(AiJob.waiting(
                requesterId,
                scopeKey,
                scopeKey + "/jobs/" + UUID.randomUUID(),
                List.of(documentId)
        ));
        parseJobLauncher.launch(job, DocumentReprocessPlan.removed(documentId, removedParsedMarkdown));
        return job;
    }

    /**
     * 문서의 현재 파싱 본문입니다. 파싱 전이거나 파일이 유실되면 {@code null}입니다.
     */
    private String readParsedMarkdownQuietly(Document document) {
        if (document.parsedPath() == null || document.parsedPath().isBlank()) {
            return null;
        }
        try {
            return documentFileStorage.readText(document.parsedPath());
        } catch (IOException exception) {
            log.warn("파싱 본문을 읽지 못했습니다: documentId={}, parsedPath={}",
                    document.id(), document.parsedPath(), exception);
            return null;
        }
    }

    private void registerFileRollback(DocumentFileMutation fileMutation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fileMutation.discardBackup();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) fileMutation.discardBackup();
                else rollbackFileMutation(fileMutation, null);
            }
        });
    }

    private void rollbackFileMutation(DocumentFileMutation fileMutation, RuntimeException original) {
        try { fileMutation.rollback(); }
        catch (IOException exception) {
            if (original != null) original.addSuppressed(exception);
            else throw new UncheckedIOException(exception);
        }
    }

    /**
     * 작업(DOC-05): 원본문서 하드 삭제. (DR-014)
     * 문서와 관련 파일을 삭제한 뒤 이 문서를 근거로 쓴 Wiki를 걷어낸다(202 + jobId).
     *
     * <p>걷어내기는 {@code document_removed}로 나가며, 반영 단계에서 남은 Wiki의
     * {@code documentRefs}에서 이 문서를 지운다. 근거가 전부 사라진 페이지의 삭제는 에이전트가
     * 변경 목록으로 지시한다(FastAPI {@code reconcile_instruction}).
     */
    @Transactional
    public DocumentDeleteResponse delete(long documentId) {
        CurrentMember admin = requireAdmin();
        Document document = findDocument(documentId);
        ensureNotInProgress(document);
        String scopeKey = document.scopeKey();
        String originalPath = document.originalPath();
        String parsedPath = document.parsedPath();
        // 같은 공간의 Wiki를 동시에 고치지 않도록 처리 중 문서가 있으면 충돌로 막는다.
        ensureScopeNotProcessing(scopeKey);

        // 파일을 지우기 전에 파싱 본문을 읽어 둔다. 걷어내기 요청의 필수값이다.
        String removedParsedMarkdown = readParsedMarkdownQuietly(document);

        documentRepository.delete(document);
        documentRepository.flush();

        AiJob job = removeDeletedDocumentFromScope(
                admin.memberId(), documentId, scopeKey, removedParsedMarkdown);

        // 수정(S15P11B106-146): 파일 삭제는 DB 트랜잭션처럼 롤백되지 않으므로, 삭제할 경로만 보관했다가
        //   커밋이 성공한 뒤에만 실제로 지운다. flush는 커밋이 아니라 여기서 바로 지우면, 이후 걷어내기 작업
        //   생성 등에서 예외가 나 트랜잭션이 롤백돼도 파일은 이미 사라져 "DB엔 문서, 파일은 없음" 불일치가 생긴다.
        //   위 DB 작업(문서 삭제 + 걷어내기 작업 생성)이 모두 성공한 뒤에 예약하므로, 그중 무엇이 실패하면
        //   파일 삭제 예약 자체가 실행되지 않는다.
        registerAfterCommitFileDelete(originalPath, parsedPath);
        // 수정(S15P11B106-93): 여기 도달했으면 삭제는 성공(실패는 위에서 예외로 처리됨). 재처리 작업이 생성됐으면
        //   reprocessRequired=true·waiting·jobId, 재처리할 내용이 없어 작업을 만들지 않았으면 false·skipped·null로
        //   내려 프론트가 "jobId=null이 정상 상황"임을 구분할 수 있게 한다.
        boolean reprocessRequired = job != null;
        return new DocumentDeleteResponse(
                true,
                reprocessRequired,
                reprocessRequired ? String.valueOf(job.id()) : null,
                scopeKey,
                reprocessRequired ? job.status().name().toLowerCase() : "skipped"
        );
    }

    // 문서 한 건만 재처리 대상(UPLOADED)으로 되돌리고 AI 작업을 생성·실행한다(증분: 업로드·재시도와 동일한 패턴).
    // 파일 교체·같은 범위 수정처럼 특정 문서만 바뀐 경우에 사용한다.
    private AiJob reprocessDocument(long requesterId, Document document) {
        return reprocessDocument(requesterId, document, DocumentReprocessPlan.added());
    }

    private AiJob reprocessDocument(long requesterId, Document document, DocumentReprocessPlan plan) {
        document.markForReprocess();
        AiJob job = aiJobRepository.save(AiJob.waiting(
                requesterId,
                document.scopeKey(),
                document.scopeKey() + "/jobs/" + UUID.randomUUID(),
                List.of(document.id())
        ));
        parseJobLauncher.launch(job, plan);
        return job;
    }

    // 범위 전체를 다시 훑던 reprocessScope는 제거했다. 문서가 빠지는 경우(범위 변경 시 기존 범위,
    // 삭제)를 document_removed 단건으로 처리하므로 남은 문서를 다시 반영할 이유가 없다.
    // 남은 문서가 0개일 때 빈 작업이 FAILED로 마감되던 허위 실패(옛 리뷰 #3)도 함께 사라졌다.

    private void ensureNotInProgress(Document document) {
        if (document.isInProgress()) {
            throw new BusinessException(ErrorCode.INVALID_DOCUMENT_STATUS);
        }
    }

    private void ensureScopeNotProcessing(String scopeKey) {
        boolean anyProcessing = documentRepository.findByScopeKey(scopeKey).stream()
                .anyMatch(Document::isInProgress);
        if (anyProcessing) {
            throw new BusinessException(ErrorCode.WIKI_EDIT_IN_PROGRESS);
        }
    }

    private void ensureWikiScope(ScopeKey scope) {
        wikiScopeRepository.findById(scope.value()).orElseGet(() -> wikiScopeRepository.save(
                "ALL".equals(scope.value())
                        ? WikiScope.all()
                        : WikiScope.department(scope.departmentIds())));
    }

    /**
     * 수정(S15P11B106-146): 삭제할 파일 경로를 보관했다가 DB 트랜잭션 커밋이 성공한 뒤에만(afterCommit) 지운다.
     *
     * <p>파일 삭제는 트랜잭션처럼 롤백되지 않으므로 커밋 전에 지우면 롤백 시 "DB엔 문서, 파일은 없음" 불일치가 생긴다.
     * afterCompletion이 아니라 afterCommit에 두는 이유는 롤백(STATUS_ROLLED_BACK)에서는 지우면 안 되기 때문이다.
     * 트랜잭션 동기화가 비활성(트랜잭션 밖 호출)일 때만 즉시 지운다. delete()는 {@code @Transactional}이라 보통
     * afterCommit 경로를 탄다. parsedPath가 null/blank이면 deleteQuietly가 안전하게 건너뛰어 originalPath만 지운다.
     */
    private void registerAfterCommitFileDelete(String originalPath, String parsedPath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteQuietly(originalPath);
            deleteQuietly(parsedPath);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                deleteQuietly(originalPath);
                deleteQuietly(parsedPath);
            }
        });
    }

    private void deleteQuietly(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return;
        }
        try {
            documentFileStorage.delete(storedPath);
        } catch (IOException ignored) {
            log.warn("원본문서 파일 삭제 실패(무시하고 진행): {}", storedPath);
        }
    }

    private DocumentDetailResponse toDetail(Document document, DocumentCategory category) {
        ScopeKey scope = ScopeKey.parse(document.scopeKey());
        return new DocumentDetailResponse(
                String.valueOf(document.id()),
                document.originalFileName(),
                document.mimeType(),
                document.fileSize(),
                String.valueOf(document.documentCategoryId()),
                category.name(),
                document.scopeKey(),
                scope.visibilityType(),
                departmentRefs(scope.departmentIds()),
                document.status().name().toLowerCase(),
                document.failureReason(),
                uploaderRef(document.uploaderId()),
                document.createdAt(),
                "/api/v1/documents/%d/file".formatted(document.id()),
                List.of()
        );
    }

    private DocumentUploaderResponse uploaderRef(long uploaderId) {
        return memberRepository.findById(uploaderId)
                .map(member -> new DocumentUploaderResponse(String.valueOf(member.getId()), member.getName()))
                .orElse(null);
    }

    private List<DocumentDepartmentResponse> departmentRefs(List<Long> departmentIds) {
        if (departmentIds.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = departmentRepository.findAllById(departmentIds).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        return departmentIds.stream()
                .map(id -> new DocumentDepartmentResponse(String.valueOf(id), names.get(id)))
                .toList();
    }

    /**
     * 작업(DOC-03/DOC-04): 원본문서 목록 조회.
     * 관리자는 전체를, 사원은 접근 가능한 문서(전체 공개 ALL + 본인 소속 부서 공개)만 조회한다(FR-ACL-002).
     * 공개범위·카테고리·상태·검색어·파일형식·부서·업로드기간 필터와 페이지네이션을 지원한다.
     * 인증은 시큐리티 계층에서 강제되므로 여기서는 역할(관리자/사원)에 따라 노출 범위만 나눈다.
     */
    @Transactional(readOnly = true)
    public DocumentListResponse findDocuments(
            Integer page,
            Integer size,
            String scopeKey,
            Long categoryId,
            String status,
            String keyword,
            String fileType,
            Long departmentId,
            String uploadedFrom,
            String uploadedTo,
            String sort
    ) {
        CurrentMember currentMember = currentMemberProvider.currentMember();

        // 접근 가능한 공개범위(scopeKey) 제한을 계산한다. null이면 제한 없음(관리자이면서 부서 필터도 없는 경우).
        Set<String> allowedScopeKeys = null;
        if (!currentMember.isAdmin()) {
            // 사원: 전체 공개(ALL) + 본인 소속 부서를 포함하는 부서 공개 범위만 볼 수 있다.
            allowedScopeKeys = accessibleScopeKeys(memberDepartmentId(currentMember.memberId()));
        }
        if (departmentId != null) {
            // departmentId 필터(계약): 해당 부서를 department_refs에 포함하는 공개범위로 좁힌다. 사원이면 기존 접근권한과 교집합.
            // 의도된 동작: 전사 공개(ALL)는 이 필터에서 제외한다. "특정 부서 전용 문서만" 보기 위한 필터이기 때문.
            Set<String> departmentScopeKeys = scopeKeysContaining(departmentId);
            allowedScopeKeys = (allowedScopeKeys == null)
                    ? departmentScopeKeys
                    : intersect(allowedScopeKeys, departmentScopeKeys);
        }

        Pageable pageable = createPageable(page, size, sort);
        Specification<Document> specification = documentSpecification(
                allowedScopeKeys, scopeKey, categoryId, status, keyword, fileType, uploadedFrom, uploadedTo);
        Page<Document> documents = documentRepository.findAll(specification, pageable);

        // 카테고리명·업로더명·공개범위 부서명을 문서마다 개별 조회하면 N+1이 되므로 페이지 단위로 한 번에 모아 매핑한다.
        // 리뷰: 매핑에 없으면(카테고리 삭제 등) 이름은 null로 내려간다(목록 자체는 정상).
        List<Document> content = documents.getContent();
        Set<Long> categoryIds = content.stream()
                .map(Document::documentCategoryId)
                .collect(Collectors.toSet());
        Map<Long, String> categoryNames = documentCategoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(DocumentCategory::id, DocumentCategory::name));

        Set<Long> uploaderIds = content.stream()
                .map(Document::uploaderId)
                .collect(Collectors.toSet());
        Map<Long, String> uploaderNames = memberRepository.findAllById(uploaderIds).stream()
                .collect(Collectors.toMap(Member::getId, Member::getName));

        Set<Long> departmentIds = content.stream()
                .flatMap(document -> ScopeKey.parse(document.scopeKey()).departmentIds().stream())
                .collect(Collectors.toSet());
        Map<Long, String> departmentNames = departmentRepository.findAllById(departmentIds).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));

        Page<DocumentSummaryResponse> mapped = documents.map(document -> {
            ScopeKey scope = ScopeKey.parse(document.scopeKey());
            List<DocumentDepartmentResponse> departmentRefs = scope.departmentIds().stream()
                    .map(id -> new DocumentDepartmentResponse(String.valueOf(id), departmentNames.get(id)))
                    .toList();
            DocumentUploaderResponse uploader = uploaderNames.containsKey(document.uploaderId())
                    ? new DocumentUploaderResponse(String.valueOf(document.uploaderId()), uploaderNames.get(document.uploaderId()))
                    : null;
            return DocumentSummaryResponse.from(
                    document, categoryNames.get(document.documentCategoryId()),
                    scope.visibilityType(), departmentRefs, uploader);
        });
        return DocumentListResponse.from(mapped);
    }

    // 리뷰: 아래 createPageable/parseSort는 MemberService와 로직이 유사하다.
    //       도메인마다 허용 정렬 필드가 달라 섣부른 공통화는 피하고, 백로그의 페이지네이션 공통 모듈(#43)에서 통합한다.
    private Pageable createPageable(Integer page, Integer size, String sort) {
        int safePage = page == null ? 1 : page;
        int safeSize = size == null ? 20 : size;
        if (safePage < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 1 이상이어야 합니다.");
        }
        if (safeSize < 1 || safeSize > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "size는 1 이상 100 이하여야 합니다.");
        }
        return PageRequest.of(safePage - 1, safeSize, parseSort(sort));
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String[] values = sort.split(",", -1);
        String property = values[0].trim();
        if (!List.of("createdAt", "updatedAt", "originalFileName").contains(property)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "허용되지 않은 정렬 기준입니다.");
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (values.length > 1 && !values[1].isBlank()) {
            try {
                direction = Sort.Direction.fromString(values[1]);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "정렬 방향은 asc 또는 desc만 사용할 수 있습니다.");
            }
        }
        return Sort.by(direction, property);
    }

    private Specification<Document> documentSpecification(
            Set<String> allowedScopeKeys,
            String scopeKey,
            Long categoryId,
            String status,
            String keyword,
            String fileType,
            String uploadedFrom,
            String uploadedTo
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (allowedScopeKeys != null) {
                // 접근 가능한 공개범위로 제한한다. 접근 가능 범위가 하나도 없으면 결과도 없어야 한다.
                predicates.add(allowedScopeKeys.isEmpty()
                        ? criteriaBuilder.disjunction()
                        : root.get("scopeKey").in(allowedScopeKeys));
            }
            if (scopeKey != null && !scopeKey.isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("scopeKey"), scopeKey));
            }
            if (categoryId != null) {
                predicates.add(criteriaBuilder.equal(root.get("documentCategoryId"), categoryId));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("status"), parseStatus(status)));
            }
            if (keyword != null && !keyword.isBlank()) {
                // 리뷰: 현재 스키마엔 문서 제목/본문 컬럼이 없어 파일명 LIKE로만 검색한다.
                //       Wiki 제목·본문 검색이 생기면 확장 대상.
                // 리뷰: 사용자 입력의 % _ \ 를 이스케이프해 와일드카드로 오작동하지 않게 한다.
                String pattern = "%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("originalFileName")), pattern, '\\'));
            }
            if (fileType != null && !fileType.isBlank()) {
                String pattern = "%." + escapeLike(fileType.trim().toLowerCase(Locale.ROOT));
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("originalFileName")), pattern, '\\'));
            }
            if (uploadedFrom != null && !uploadedFrom.isBlank()) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), parseDateStart(uploadedFrom)));
            }
            if (uploadedTo != null && !uploadedTo.isBlank()) {
                predicates.add(criteriaBuilder.lessThan(root.get("createdAt"), parseDateEndExclusive(uploadedTo)));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private DocumentStatus parseStatus(String value) {
        try {
            return DocumentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "허용되지 않은 문서 상태입니다.");
        }
    }

    private Instant parseDateStart(String value) {
        return parseDate(value).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant parseDateEndExclusive(String value) {
        return parseDate(value).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "날짜 형식은 YYYY-MM-DD여야 합니다.");
        }
    }

    /** LIKE 패턴에서 특수문자(% _ \)를 리터럴로 취급하도록 escape한다. escape 문자는 역슬래시(\). */
    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private Long memberDepartmentId(long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        return member.getDepartment().getId();
    }

    /** 사원이 접근 가능한 공개범위: 전체 공개(ALL) + 소속 부서를 포함하는 부서 공개 범위(FR-ACL-002/005). */
    private Set<String> accessibleScopeKeys(Long departmentId) {
        Set<String> scopeKeys = new HashSet<>();
        scopeKeys.add("ALL");
        if (departmentId != null) {
            scopeKeys.addAll(scopeKeysContaining(departmentId));
        }
        return scopeKeys;
    }

    /**
     * 해당 부서를 department_refs에 포함하는 부서 공개 scopeKey 집합.
     * department_refs가 JSON이라 DB 조인이 불가하므로(backend-spring-convention 3절) 메모리에서 판정한다.
     */
    private Set<String> scopeKeysContaining(long departmentId) {
        return wikiScopeRepository.findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT).stream()
                .filter(scope -> scope.departmentRefs().contains(departmentId))
                .map(WikiScope::scopeKey)
                .collect(Collectors.toSet());
    }

    private static Set<String> intersect(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.retainAll(right);
        return result;
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
