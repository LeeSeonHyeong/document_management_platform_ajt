package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.model.WikiScopeVisibilityType;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.service.CurrentMember;
import com.ajt.backend.domain.document.service.CurrentMemberProvider;
import com.ajt.backend.domain.member.DepartmentScopePolicy;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.wiki.api.WikiCategoryListResponse;
import com.ajt.backend.domain.wiki.api.WikiDetailResponse;
import com.ajt.backend.domain.wiki.api.WikiListResponse;
import com.ajt.backend.domain.wiki.api.WikiSpaceListResponse;
import com.ajt.backend.domain.wiki.api.WikiSpaceResponse;
import com.ajt.backend.domain.wiki.api.WikiSummaryResponse;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wiki 조회 서비스입니다.
 * Wiki 목록·검색(WIKI-01)과 Wiki 공간 목록, 공간별 카테고리 목록, Wiki 상세 조회를 담당합니다.
 * 최고관리자는 전체를, 부서관리자와 사원은 접근 가능한 Wiki(전체 공개 ALL + 본인 소속 부서 공개)만
 * 조회한다(FR-ACL-002·003, 조회 범위는 S15P11B106-229에서 부서관리자를 사원과 동일하게 되돌림).
 */
@Service
public class WikiQueryService {

    private static final Pattern SCOPE_KEY_PATTERN = Pattern.compile("ALL|D[1-9][0-9]*(-D[1-9][0-9]*)*");

    private final CurrentMemberProvider currentMemberProvider;
    private final WikiRepository wikiRepository;
    private final WikiCategoryRepository wikiCategoryRepository;
    private final WikiFileStorage wikiFileStorage;
    // 사원 접근권한 판정용(소속 부서 → 접근 가능한 공개범위). document 도메인의 것을 재사용한다.
    // TODO(팀 협업): WikiScope가 document 도메인에 있어 wiki 도메인이 document 저장소에 의존한다.
    //  (기존 WikiChatMessageService도 동일) WikiScope를 공용/wiki 도메인으로 옮길지 팀과 정리한다.
    private final WikiScopeRepository wikiScopeRepository;
    private final MemberRepository memberRepository;
    private final DocumentRepository documentRepository;
    private final DepartmentRepository departmentRepository;
    private final DepartmentScopePolicy departmentScopePolicy;

    public WikiQueryService(
            CurrentMemberProvider currentMemberProvider,
            WikiRepository wikiRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiFileStorage wikiFileStorage,
            WikiScopeRepository wikiScopeRepository,
            MemberRepository memberRepository,
            DocumentRepository documentRepository,
            DepartmentRepository departmentRepository,
            DepartmentScopePolicy departmentScopePolicy
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.wikiRepository = wikiRepository;
        this.wikiCategoryRepository = wikiCategoryRepository;
        this.wikiFileStorage = wikiFileStorage;
        this.wikiScopeRepository = wikiScopeRepository;
        this.memberRepository = memberRepository;
        this.documentRepository = documentRepository;
        this.departmentRepository = departmentRepository;
        this.departmentScopePolicy = departmentScopePolicy;
    }

    // ===== WIKI-01 Wiki 목록·검색 =====

    @Transactional(readOnly = true)
    public WikiListResponse findWikis(
            Integer page,
            Integer size,
            String scopeKey,
            Long wikiCategoryId,
            String keyword,
            String sort
    ) {
        CurrentMember currentMember = currentMemberProvider.currentMember();

        // 조회 권한(S15P11B106-229): 최고관리자만 전체 Wiki 조회(제한 없음, null). 부서관리자는 사원과
        //   동일하게 전체공개(ALL) + 본인 소속 부서가 포함된 Wiki를 조회한다. (콘텐츠 관리 제한은 별도로 유지.)
        Set<String> allowedScopeKeys = isSuperAdmin(currentMember)
                ? null
                : accessibleScopeKeys(memberDepartmentId(currentMember.memberId()));

        Pageable pageable = createPageable(page, size, sort);
        Specification<Wiki> specification = wikiSpecification(allowedScopeKeys, scopeKey, wikiCategoryId, keyword);
        Page<Wiki> wikis = wikiRepository.findAll(specification, pageable);

        // 카테고리명은 페이지의 카테고리 ID를 한 번에 모아 매핑한다(N+1 방지).
        Set<Long> categoryIds = wikis.getContent().stream()
                .map(Wiki::wikiCategoryId)
                .collect(Collectors.toSet());
        Map<Long, String> categoryNames = wikiCategoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(WikiCategory::id, WikiCategory::name));

        // 요약은 wiki 테이블에 없어 공간 목차(index.md)에서 읽는다. 같은 공간은 목차를 한 번만 읽는다.
        Map<String, WikiIndex> indexByScope = new HashMap<>();
        Page<WikiSummaryResponse> mapped = wikis.map(wiki -> {
            WikiIndex index = indexByScope.computeIfAbsent(wiki.scopeKey(), this::readIndexSafely);
            return new WikiSummaryResponse(
                    String.valueOf(wiki.id()),
                    wiki.title(),
                    index.summaryOf(wiki.id()),
                    String.valueOf(wiki.wikiCategoryId()),
                    categoryNames.get(wiki.wikiCategoryId()),
                    wiki.scopeKey(),
                    wiki.updatedAt()
            );
        });
        return WikiListResponse.from(mapped);
    }

    // ===== Wiki 공간 목록 =====

    @Transactional(readOnly = true)
    public WikiSpaceListResponse findAccessibleSpaces() {
        AccessScope access = accessScopeOf(currentMemberProvider.currentMember());

        // TODO(팀 협업): 현재는 모든 공간을 읽어와 메모리에서 접근 필터링한다. 공간(부서 조합) 수가 커지면
        //  visibility_type='ALL' OR JSON_CONTAINS(department_refs, myDept) 형태의 쿼리로 내려야 한다.
        //  department_refs가 JSON 컬럼이라 DB 종속적이므로 도입 시점·방식은 팀과 협의한다.
        List<WikiScope> accessibleScopes = wikiScopeRepository.findAll().stream()
                .filter(access::canAccess)
                .toList();

        Map<Long, String> departmentNames = resolveDepartmentNames(accessibleScopes);
        Map<String, Long> wikiCounts = resolveWikiCounts(accessibleScopes);
        List<WikiSpaceResponse> items = accessibleScopes.stream()
                .map(scope -> toSpaceResponse(scope, departmentNames, wikiCounts))
                .toList();
        return new WikiSpaceListResponse(items);
    }

    // ===== Wiki 카테고리 목록 =====

    @Transactional(readOnly = true)
    public WikiCategoryListResponse findCategories(String scopeKey) {
        AccessScope access = accessScopeOf(currentMemberProvider.currentMember());
        String validatedScopeKey = validateScopeKey(scopeKey);
        WikiScope scope = wikiScopeRepository.findById(validatedScopeKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_SCOPE_NOT_FOUND));
        if (!access.canAccess(scope)) {
            throw new BusinessException(ErrorCode.WIKI_SCOPE_NOT_FOUND);
        }
        return WikiCategoryListResponse.from(
                wikiCategoryRepository.findAllByScopeKeyOrderByNameAsc(validatedScopeKey));
    }

    // ===== Wiki 상세 =====

    @Transactional(readOnly = true)
    public WikiDetailResponse getWiki(long wikiId) {
        return toDetail(findAccessibleWiki(wikiId));
    }

    // ===== Wiki 파일 다운로드 =====

    /**
     * Wiki 본문을 Markdown 파일로 내려준다. 위키 상세(getWiki)와 같은 접근 권한 검사를 쓴다.
     * 원본문서 다운로드({@code GET /documents/:documentId/file})와 달리 실제 저장 파일이 아니라
     * 조회 시점의 본문을 그대로 메모리에서 파일로 감싼다 — Wiki 본문은 하나의 정본 파일(DR-001)이라
     * 별도 다운로드 사본을 저장소에 두지 않는다.
     */
    @Transactional(readOnly = true)
    public WikiFileDownload downloadFile(long wikiId) {
        Wiki wiki = findAccessibleWiki(wikiId);
        String content = readWikiContent(wiki);
        Resource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
        String fileName = wiki.title().replace("/", "_") + ".md";
        return new WikiFileDownload(resource, fileName, "text/markdown; charset=UTF-8");
    }

    private Wiki findAccessibleWiki(long wikiId) {
        AccessScope access = accessScopeOf(currentMemberProvider.currentMember());
        Wiki wiki = wikiRepository.findById(wikiId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_NOT_FOUND));
        WikiScope scope = wikiScopeRepository.findById(wiki.scopeKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_NOT_FOUND));
        if (!access.canAccess(scope)) {
            throw new BusinessException(ErrorCode.WIKI_NOT_FOUND);
        }
        return wiki;
    }

    // ===== 접근 권한 =====

    private AccessScope accessScopeOf(CurrentMember member) {
        // 수정(S15P11B106-229): Wiki '조회'는 최고관리자만 전체 접근이고, 부서관리자는 사원과 동일하게
        //   전체공개(ALL) + 본인 소속 부서 포함 Wiki만 조회한다. (콘텐츠 관리 제한은 WikiChatMessageService 등
        //   관리 경로에서 별도로 유지한다.)
        if (isSuperAdmin(member)) {
            return AccessScope.forSuperAdmin();
        }
        return AccessScope.forEmployee(memberDepartmentId(member.memberId()));
    }

    // 최고관리자 여부(설정 이메일 기준). 부서관리자·사원은 false → 조회 시 사원과 동일한 범위를 쓴다.
    private boolean isSuperAdmin(CurrentMember member) {
        return member.isAdmin() && departmentScopePolicy.resolve(member.memberId()).isSuperAdmin();
    }

    // 수정(S15P11B106-229): 조회 권한은 '최고관리자 전체' vs '그 외(부서관리자·사원) 동일'만 구분한다.
    private record AccessScope(boolean superAdmin, Long departmentId) {

        static AccessScope forSuperAdmin() {
            return new AccessScope(true, null);
        }

        static AccessScope forEmployee(Long departmentId) {
            return new AccessScope(false, departmentId);
        }

        boolean canAccess(WikiScope scope) {
            if (superAdmin) {
                return true;
            }
            // 부서관리자·사원: 전체 공개(ALL) + 소속 부서를 포함하는 부서 공개 범위(복수부서 포함).
            if (scope.visibilityType() == WikiScopeVisibilityType.ALL) {
                return true;
            }
            return departmentId != null && scope.departmentRefs().contains(departmentId);
        }
    }

    // TODO(공통화): 소속 부서 → 접근 가능 scopeKey 판정이 schedule/document/wiki 세 도메인에 중복돼 있다.
    //  공통 컴포넌트(예: ScopeAccessResolver)로 추출 예정 — 후속 과제. (지금은 도메인별 차이가 있어 섣부른 추상화는 보류)
    private Long memberDepartmentId(long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        return member.getDepartment().getId();
    }

    /** 사원이 접근 가능한 공개범위: 전체 공개(ALL) + 소속 부서를 포함하는 부서 공개 범위. */
    private Set<String> accessibleScopeKeys(Long departmentId) {
        Set<String> scopeKeys = new HashSet<>();
        scopeKeys.add("ALL");
        if (departmentId != null) {
            wikiScopeRepository.findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT).stream()
                    .filter(scope -> scope.departmentRefs().contains(departmentId))
                    .forEach(scope -> scopeKeys.add(scope.scopeKey()));
        }
        return scopeKeys;
    }

    // ===== 목록 검색 스펙 / 페이지네이션 =====

    private Specification<Wiki> wikiSpecification(
            Set<String> allowedScopeKeys,
            String scopeKey,
            Long wikiCategoryId,
            String keyword
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (allowedScopeKeys != null) {
                // 접근 가능한 공개범위로 제한. 접근 가능 범위가 없으면 결과도 없다.
                predicates.add(allowedScopeKeys.isEmpty()
                        ? criteriaBuilder.disjunction()
                        : root.get("scopeKey").in(allowedScopeKeys));
            }
            if (scopeKey != null && !scopeKey.isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("scopeKey"), scopeKey));
            }
            if (wikiCategoryId != null) {
                predicates.add(criteriaBuilder.equal(root.get("wikiCategoryId"), wikiCategoryId));
            }
            if (keyword != null && !keyword.isBlank()) {
                // 리뷰: 본문은 파일(wiki_path)에 있어 목록 쿼리에서 검색이 어렵다. 지금은 제목만 검색한다.
                //       본문 검색은 요약 컬럼(TODO(DB)) 또는 전문색인 도입 후 확장 대상.
                String pattern = "%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), pattern, '\\'));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private WikiIndex readIndexSafely(String scopeKey) {
        try {
            return WikiIndex.parse(wikiFileStorage.readIndex(scopeKey));
        } catch (IOException exception) {
            // 목차를 읽지 못하면 요약 없이 목록만 내려준다(목록 자체는 정상).
            return WikiIndex.parse(null);
        }
    }

    // TODO(#43): createPageable/parseSort가 member/document 등 여러 도메인에 중복된다.
    //  페이지네이션·정렬 공통 모듈(#43)에서 통합 예정 — 후속 과제.
    private Pageable createPageable(Integer page, Integer size, String sort) {
        int safePage = page == null ? 1 : page;
        int safeSize = size == null ? 20 : size;
        if (safePage < 1) {
            throw new BusinessException(ErrorCode.INVALID_WIKI_FILTER, "page는 1 이상이어야 합니다.");
        }
        if (safeSize < 1 || safeSize > 100) {
            throw new BusinessException(ErrorCode.INVALID_WIKI_FILTER, "size는 1 이상 100 이하여야 합니다.");
        }
        return PageRequest.of(safePage - 1, safeSize, parseSort(sort));
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "updatedAt");
        }
        String[] values = sort.split(",", -1);
        String property = values[0].trim();
        if (!List.of("updatedAt", "createdAt", "title").contains(property)) {
            throw new BusinessException(ErrorCode.INVALID_WIKI_FILTER, "허용되지 않은 정렬 기준입니다.");
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (values.length > 1 && !values[1].isBlank()) {
            try {
                direction = Sort.Direction.fromString(values[1]);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.INVALID_WIKI_FILTER, "정렬 방향은 asc 또는 desc만 사용할 수 있습니다.");
            }
        }
        return Sort.by(direction, property);
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    // ===== 공간/상세 매핑 =====

    private WikiSpaceResponse toSpaceResponse(
            WikiScope scope,
            Map<Long, String> departmentNames,
            Map<String, Long> wikiCounts
    ) {
        List<WikiSpaceResponse.Department> departments = scope.departmentRefs().stream()
                .map(departmentId -> new WikiSpaceResponse.Department(
                        String.valueOf(departmentId),
                        departmentNames.getOrDefault(departmentId, "")
                ))
                .toList();
        return new WikiSpaceResponse(
                scope.scopeKey(),
                scope.visibilityType().name().toLowerCase(Locale.ROOT),
                departments,
                displayName(scope, departments),
                wikiCounts.getOrDefault(scope.scopeKey(), 0L)
        );
    }

    private String displayName(WikiScope scope, List<WikiSpaceResponse.Department> departments) {
        if (scope.visibilityType() == WikiScopeVisibilityType.ALL) {
            // 문서 화면과 같은 표기('전체 공개')를 쓴다. 이전 값 '전체'는 Wiki 사이드바의
            // 스코프 필터 항목('전체 부서')과 나란히 놓였을 때 무엇이 공개 범위인지 구분되지 않았다.
            return "전체 공개";
        }
        return departments.stream()
                .map(WikiSpaceResponse.Department::name)
                .filter(name -> !name.isBlank())
                .reduce((left, right) -> left + " + " + right)
                .orElse(scope.scopeKey());
    }

    private Map<Long, String> resolveDepartmentNames(List<WikiScope> scopes) {
        List<Long> departmentIds = scopes.stream()
                .flatMap(scope -> scope.departmentRefs().stream())
                .distinct()
                .toList();
        Map<Long, String> names = new LinkedHashMap<>();
        departmentRepository.findAllById(departmentIds)
                .forEach(department -> names.put(department.getId(), department.getName()));
        return names;
    }

    /**
     * 접근 가능한 공간들의 Wiki 개수를 한 번의 집계 쿼리로 구합니다.
     */
    private Map<String, Long> resolveWikiCounts(List<WikiScope> scopes) {
        if (scopes.isEmpty()) {
            return Map.of();
        }
        List<String> scopeKeys = scopes.stream().map(WikiScope::scopeKey).toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        wikiRepository.countByScopeKeys(scopeKeys)
                .forEach(row -> counts.put(row.getScopeKey(), row.getWikiCount()));
        return counts;
    }

    // TODO(팀 협업): 아래 Wiki 상세 조립 로직은 WikiChatMessageService.toDetail과 사실상 동일하다.
    //  공용 컴포넌트(WikiDetailAssembler 등)로 추출해 양쪽이 함께 쓰도록 통합할지 팀과 협의한다.
    //  무방향 판정이 두 곳에 있다 — 위 TODO 의 추출 대상.
    private WikiDetailResponse toDetail(Wiki wiki) {
        return new WikiDetailResponse(
                String.valueOf(wiki.id()),
                wiki.title(),
                readWikiContent(wiki),
                category(wiki.wikiCategoryId()),
                wiki.scopeKey(),
                evidenceDocumentSummaries(wiki.documentRefs()),
                relatedWikis(wiki.scopeKey(), wiki.id(), wiki.wikiRefs()),
                wiki.updatedAt()
        );
    }

    private WikiDetailResponse.Category category(long wikiCategoryId) {
        return wikiCategoryRepository.findById(wikiCategoryId)
                .map(category -> new WikiDetailResponse.Category(
                        String.valueOf(category.id()),
                        category.name()
                ))
                .orElse(null);
    }

    private List<WikiDetailResponse.EvidenceDocument> evidenceDocumentSummaries(List<Long> documentRefs) {
        if (documentRefs.isEmpty()) {
            return List.of();
        }
        Map<Long, Document> documentsById = new LinkedHashMap<>();
        documentRepository.findAllById(documentRefs)
                .forEach(document -> documentsById.put(document.id(), document));
        return documentRefs.stream()
                .map(documentsById::get)
                .filter(document -> document != null)
                .map(document -> WikiDetailResponse.EvidenceDocument.of(
                        document.id(),
                        document.originalFileName()
                ))
                .toList();
    }

    /**
     * 관련 Wiki 는 무방향입니다 (FR-WIKI-010). 내 목록에 있는 것과 <b>나를 가리키는 것</b>을
     * 합쳐 돌려줍니다.
     *
     * <p>저장이 양쪽 JSON 을 함께 바꾸므로(DR-003) 원칙적으로는 내 목록만 읽어도 충분합니다.
     * 역방향까지 읽는 이유는 두 가지입니다 — 반영 경로가 source 에만 쓰던 기간에 쌓인 반쪽
     * 관계가 보정 전에도 화면에 맞게 보여야 하고, 이후 어떤 경로로 반쪽이 생겨도 화면은
     * 옳아야 합니다. {@code InternalWikiQueryService#relations} 가 이미 같은 방식입니다.
     *
     * <p>순서는 내 목록의 저장 순서 → 나를 가리키는 쪽 id 순이며, 요청마다 달라지면 안 됩니다.
     */
    private List<WikiDetailResponse.RelatedWiki> relatedWikis(
            String scopeKey, long wikiId, List<Long> wikiRefs) {
        List<Wiki> inScope = wikiRepository.findAllByScopeKey(scopeKey);
        Map<Long, Wiki> inScopeById = new LinkedHashMap<>();
        inScope.forEach(candidate -> inScopeById.put(candidate.id(), candidate));

        // 내 목록은 저장된 순서를 지킨다. 화면 순서가 요청마다 흔들리지 않아야 한다.
        Map<Long, Wiki> relatedById = new LinkedHashMap<>();
        for (Long refId : wikiRefs) {
            Wiki related = inScopeById.get(refId);
            if (related != null) {
                relatedById.put(refId, related);
            }
        }
        // 나를 가리키는 쪽은 저장된 순서가 없으므로 id 순으로 고정한다.
        inScope.stream()
                .filter(candidate -> candidate.id() != wikiId)
                .filter(candidate -> candidate.wikiRefs().contains(wikiId))
                .sorted(Comparator.comparing(Wiki::id))
                .forEach(candidate -> relatedById.putIfAbsent(candidate.id(), candidate));

        return relatedById.values().stream()
                .map(related -> new WikiDetailResponse.RelatedWiki(
                        String.valueOf(related.id()),
                        related.title()
                ))
                .toList();
    }

    private String readWikiContent(Wiki wiki) {
        if (wiki.wikiPath() == null || wiki.wikiPath().isBlank()) {
            return "";
        }
        try {
            return wikiFileStorage.readWikiMarkdown(wiki.wikiPath());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String validateScopeKey(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SCOPE_KEY);
        }
        String trimmed = scopeKey.trim();
        if (!SCOPE_KEY_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessException(ErrorCode.INVALID_SCOPE_KEY);
        }
        return trimmed;
    }
}
