package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.api.DocumentDetailResponse;
import com.ajt.backend.domain.document.api.DocumentListResponse;
import com.ajt.backend.domain.document.api.DocumentRetryResponse;
import com.ajt.backend.domain.document.api.DocumentSummaryResponse;
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
