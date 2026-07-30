package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.ScopeKey;
import com.ajt.backend.domain.document.api.DocumentDeleteResponse;
import com.ajt.backend.domain.document.api.DocumentDetailResponse;
import com.ajt.backend.domain.document.api.DocumentListResponse;
import com.ajt.backend.domain.document.api.DocumentMetadataUpdateRequest;
import com.ajt.backend.domain.document.api.DocumentRetryResponse;
import com.ajt.backend.domain.document.api.DocumentSummaryResponse;
import com.ajt.backend.domain.document.api.DocumentUpdateResponse;
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
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import java.io.IOException;
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

    public DocumentManagementService(
            CurrentMemberProvider currentMemberProvider,
            DocumentRepository documentRepository,
            DocumentCategoryRepository documentCategoryRepository,
            AiJobRepository aiJobRepository,
            DocumentParseJobLauncher parseJobLauncher,
            DocumentFileStorage documentFileStorage,
            MemberRepository memberRepository,
            WikiScopeRepository wikiScopeRepository
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.documentRepository = documentRepository;
        this.documentCategoryRepository = documentCategoryRepository;
        this.aiJobRepository = aiJobRepository;
        this.parseJobLauncher = parseJobLauncher;
        this.documentFileStorage = documentFileStorage;
        this.memberRepository = memberRepository;
        this.wikiScopeRepository = wikiScopeRepository;
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
            // 범위가 바뀌면 기존 범위는 문서가 빠진 채 인덱스를 다시 만들어야 해 범위 전체를 재처리한다.
            // 이때 기존 범위에 처리 중 문서가 있으면 안전하게 재생성할 수 없어 충돌로 막는다.
            ensureScopeNotProcessing(oldScopeKey);
        }

        document.changeCategoryAndScope(request.documentCategoryId(), newScopeKey);

        // 새(또는 같은) 범위에는 이 문서만 증분 재처리한다(업로드·재시도와 동일한 문서 단위 패턴).
        AiJob job = reprocessDocument(admin.memberId(), document);
        if (scopeChanged) {
            reprocessScope(admin.memberId(), oldScopeKey);
        }

        return new DocumentUpdateResponse(
                String.valueOf(job.id()),
                job.status().name().toLowerCase(),
                toDetail(document, category)
        );
    }

    /**
     * 작업(DOC-05): 원본문서 하드 삭제.
     * 문서와 관련 파일을 삭제한 뒤 해당 범위 Wiki 재처리를 트리거한다(202 + jobId).
     *
     * <p>한계: 삭제된 문서가 만든 Wiki의 "확정 삭제"(고아 Wiki 제거)는 아직 하지 않는다.
     * 현재는 남은 문서 재처리 트리거까지이며, 실제 Wiki 반영·정리는 AI/Wiki 연동 이후에 이뤄진다.
     * TODO(위키): 문서 삭제 시 이 문서를 참조하는 Wiki의 documentRefs에서 문서를 제거하고,
     *  참조가 0이 된 Wiki를 삭제(파일·목차·관계 정리)한다. wiki 도메인/AI 연동 후 별도 티켓으로 진행.
     */
    @Transactional
    public DocumentDeleteResponse delete(long documentId) {
        CurrentMember admin = requireAdmin();
        Document document = findDocument(documentId);
        ensureNotInProgress(document);
        String scopeKey = document.scopeKey();
        String originalPath = document.originalPath();
        String parsedPath = document.parsedPath();
        // 삭제 후 남은 문서로 범위 인덱스를 다시 만들어야 하므로 범위 단위로 재처리한다. 처리 중 문서가 있으면 충돌.
        ensureScopeNotProcessing(scopeKey);

        documentRepository.delete(document);
        documentRepository.flush();
        deleteQuietly(originalPath);
        if (parsedPath != null) {
            deleteQuietly(parsedPath);
        }

        // 남은 문서 기준 범위 재처리를 트리거한다(실제 Wiki 반영·고아 Wiki 정리는 AI/Wiki 연동 후).
        AiJob job = reprocessScope(admin.memberId(), scopeKey);
        return new DocumentDeleteResponse(
                String.valueOf(job.id()),
                scopeKey,
                job.status().name().toLowerCase()
        );
    }

    // 문서 한 건만 재처리 대상(UPLOADED)으로 되돌리고 AI 작업을 생성·실행한다(증분: 업로드·재시도와 동일한 패턴).
    // 파일 교체·같은 범위 수정처럼 특정 문서만 바뀐 경우에 사용한다.
    private AiJob reprocessDocument(long requesterId, Document document) {
        document.markForReprocess();
        AiJob job = aiJobRepository.save(AiJob.waiting(
                requesterId,
                document.scopeKey(),
                document.scopeKey() + "/jobs/" + UUID.randomUUID(),
                List.of(document.id())
        ));
        parseJobLauncher.launch(job);
        return job;
    }

    // 해당 범위의 현재 문서들을 재처리 대상(UPLOADED)으로 되돌리고 재처리 AI 작업을 생성·실행한다.
    // 문서가 빠지는 경우(범위 변경 시 기존 범위, 삭제)의 인덱스 재생성에 사용한다.
    // 리뷰(#3): 남은 문서가 0개면 documentIds가 비어 워커가 빈 작업을 FAILED로 마감한다(허위 실패).
    //          "빈 범위 = Wiki 비우기"는 AI 측 처리 합의가 필요하며, 계약상 202+jobId는 그대로 반환된다. 후속 과제.
    private AiJob reprocessScope(long requesterId, String scopeKey) {
        List<Document> documents = documentRepository.findByScopeKey(scopeKey);
        documents.forEach(Document::markForReprocess);
        List<Long> documentIds = documents.stream().map(Document::id).toList();
        AiJob job = aiJobRepository.save(AiJob.waiting(
                requesterId,
                scopeKey,
                scopeKey + "/jobs/" + UUID.randomUUID(),
                documentIds
        ));
        parseJobLauncher.launch(job);
        return job;
    }

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

    private void deleteQuietly(String storedPath) {
        try {
            documentFileStorage.delete(storedPath);
        } catch (IOException ignored) {
            log.warn("원본문서 파일 삭제 실패(무시하고 진행): {}", storedPath);
        }
    }

    private DocumentDetailResponse toDetail(Document document, DocumentCategory category) {
        return new DocumentDetailResponse(
                String.valueOf(document.id()),
                document.originalFileName(),
                document.scopeKey(),
                new DocumentDetailResponse.CategoryResponse(String.valueOf(category.id()), category.name()),
                document.status().name().toLowerCase(),
                document.failureReason(),
                "/api/v1/documents/%d/file".formatted(document.id()),
                List.of(),
                document.createdAt(),
                document.updatedAt()
        );
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

        // 카테고리 이름은 문서마다 개별 조회하면 N+1이 되므로, 페이지의 카테고리 ID를 한 번에 모아 매핑한다.
        // 리뷰: 카테고리가 삭제되어 매핑에 없으면 이름은 null로 내려간다(목록 자체는 정상). 카테고리 보호 정책상 실무 영향 적음.
        Set<Long> categoryIds = documents.getContent().stream()
                .map(Document::documentCategoryId)
                .collect(Collectors.toSet());
        Map<Long, String> categoryNames = documentCategoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(DocumentCategory::id, DocumentCategory::name));

        Page<DocumentSummaryResponse> mapped = documents.map(
                document -> DocumentSummaryResponse.from(document, categoryNames.get(document.documentCategoryId())));
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
