package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.document.model.WikiScopeVisibilityType;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.service.CurrentMember;
import com.ajt.backend.domain.document.service.CurrentMemberProvider;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.wiki.api.WikiListResponse;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업(WIKI-01): Wiki 목록·검색 조회.
 * 관리자는 전체를, 사원은 접근 가능한 Wiki(전체 공개 ALL + 본인 소속 부서 공개)만 조회한다(FR-ACL-002).
 * 공개범위·카테고리·검색어 필터와 페이지네이션을 지원한다.
 */
@Service
public class WikiQueryService {

    private final CurrentMemberProvider currentMemberProvider;
    private final WikiRepository wikiRepository;
    private final WikiCategoryRepository wikiCategoryRepository;
    private final WikiFileStorage wikiFileStorage;
    // 사원 접근권한 판정용(소속 부서 → 접근 가능한 공개범위). document 도메인의 것을 재사용한다.
    private final WikiScopeRepository wikiScopeRepository;
    private final MemberRepository memberRepository;

    public WikiQueryService(
            CurrentMemberProvider currentMemberProvider,
            WikiRepository wikiRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiFileStorage wikiFileStorage,
            WikiScopeRepository wikiScopeRepository,
            MemberRepository memberRepository
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.wikiRepository = wikiRepository;
        this.wikiCategoryRepository = wikiCategoryRepository;
        this.wikiFileStorage = wikiFileStorage;
        this.wikiScopeRepository = wikiScopeRepository;
        this.memberRepository = memberRepository;
    }

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

        // 사원은 접근 가능한 공개범위(전체 + 내 부서)로 제한한다. 관리자는 제한 없음(null).
        Set<String> allowedScopeKeys = currentMember.isAdmin()
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
}
