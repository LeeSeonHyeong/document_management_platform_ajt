package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.ScopeKey;
import com.ajt.backend.domain.document.api.DocumentCategoryCreateRequest;
import com.ajt.backend.domain.document.api.DocumentCategoryListResponse;
import com.ajt.backend.domain.document.api.DocumentCategoryResponse;
import com.ajt.backend.domain.document.api.DocumentCategoryUpdateRequest;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.DepartmentScopePolicy;
import com.ajt.backend.domain.member.ScopeAccess;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.ScopeAccess;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentCategoryService {

    private final DocumentCategoryRepository documentCategoryRepository;
    private final DocumentRepository documentRepository;
    private final WikiScopeRepository wikiScopeRepository;
    private final DepartmentScopePolicy departmentScopePolicy;
    private final MemberRepository memberRepository;

    /**
     * CAT-01 문서 카테고리 목록 조회 기능입니다.
     * 같은 Wiki 공간(scopeKey)에 속한 카테고리만 이름순으로 보여줍니다.
     */
    @Transactional(readOnly = true)
    public DocumentCategoryListResponse findCategories(AuthenticatedMember loginMember, String scopeKey) {
        String normalizedScopeKey = normalizeScopeKey(scopeKey);
        requireExistingScope(normalizedScopeKey);
        // 수정(S15P11B106-199): 로그인 사용자가 접근할 수 있는 공개 범위인지 검사한다.
        //   최고관리자=모든 범위, 부서관리자=담당 부서 단일 범위, 사원=전체(ALL)+본인 부서. 그 외는 존재를 숨겨 404.
        requireReadableScope(loginMember, normalizedScopeKey);

        return DocumentCategoryListResponse.from(
                documentCategoryRepository.findAllByScopeKeyOrderByNameAsc(normalizedScopeKey)
        );
    }

    /**
     * CAT-01b 문서 카테고리 전체 목록 조회입니다(S15P11B106-290).
     * scopeKey 없이 요청하면, 로그인 관리자가 접근할 수 있는 모든 공개 범위의 카테고리를 반환한다.
     * 최고관리자=전체, 부서관리자=담당 부서 범위만. 응답 항목에 scopeKey가 있어 프론트가 부서별로 묶는다.
     * 카테고리 관리 화면(부서별 카드 조회)이 사용한다 — scopeKey 하나로만 조회하던 한계를 없앤다.
     */
    @Transactional(readOnly = true)
    public DocumentCategoryListResponse findAccessibleCategories(AuthenticatedMember loginMember) {
        requireAdmin(loginMember);
        ScopeAccess scope = departmentScopePolicy.resolve(loginMember.memberId());
        List<DocumentCategory> categories = documentCategoryRepository.findAll(Sort.by("scopeKey", "name"));
        if (!scope.isSuperAdmin()) {
            categories = categories.stream()
                    .filter(category -> scope.canAccessScopeKey(category.scopeKey()))
                    .toList();
        }
        return DocumentCategoryListResponse.from(categories);
    }

    /**
     * CAT-02 문서 카테고리 생성 기능입니다.
     * 관리자가 같은 Wiki 공간 안에서 중복되지 않는 이름으로 카테고리를 만듭니다.
     */
    @Transactional
    public DocumentCategoryResponse createCategory(
            AuthenticatedMember loginMember,
            DocumentCategoryCreateRequest request
    ) {
        requireAdmin(loginMember);
        String scopeKey = normalizeScopeKey(request.scopeKey());
        // 수정(S15P11B106-199): 부서관리자는 담당 부서 단일 범위로만 카테고리를 만들 수 있다(전체·타부서·복수 범위 403).
        requireManageableScope(loginMember, scopeKey, ErrorCode.FORBIDDEN);
        String name = normalizeName(request.name());
        ensureScope(scopeKey);
        validateDuplicateName(scopeKey, name);

        DocumentCategory category = DocumentCategory.create(scopeKey, name, request.description());
        return DocumentCategoryResponse.from(documentCategoryRepository.save(category));
    }

    /**
     * CAT-03 문서 카테고리 수정 기능입니다.
     * 이름은 보낸 경우에만 바꾸고, 설명은 null을 보내면 비우는 값으로 처리합니다.
     */
    @Transactional
    public DocumentCategoryResponse updateCategory(
            AuthenticatedMember loginMember,
            Long categoryId,
            DocumentCategoryUpdateRequest request
    ) {
        requireAdmin(loginMember);
        DocumentCategory category = findCategory(categoryId);
        // 수정(S15P11B106-199): 부서관리자는 담당 부서 범위 카테고리만 수정할 수 있다. 담당 밖은 존재를 숨겨 404.
        requireManageableScope(loginMember, category.scopeKey(), ErrorCode.DOCUMENT_CATEGORY_NOT_FOUND);

        if (request.name() != null) {
            String name = normalizeName(request.name());
            validateDuplicateName(category.scopeKey(), name, category.id());
            category.changeName(name);
        }
        if (request.descriptionPresent()) {
            category.changeDescription(request.description());
        }
        return DocumentCategoryResponse.from(category);
    }

    /**
     * CAT-04 문서 카테고리 삭제 기능입니다.
     * 이미 문서가 사용 중인 카테고리는 문서 분류가 깨지지 않도록 삭제하지 않습니다.
     */
    @Transactional
    public void deleteCategory(AuthenticatedMember loginMember, Long categoryId) {
        requireAdmin(loginMember);
        DocumentCategory category = findCategory(categoryId);
        // 수정(S15P11B106-199): 부서관리자는 담당 부서 범위 카테고리만 삭제할 수 있다. 담당 밖은 존재를 숨겨 404.
        requireManageableScope(loginMember, category.scopeKey(), ErrorCode.DOCUMENT_CATEGORY_NOT_FOUND);
        if (documentRepository.existsByDocumentCategoryId(category.id())) {
            throw new BusinessException(ErrorCode.DOCUMENT_CATEGORY_IN_USE);
        }
        documentCategoryRepository.delete(category);
    }

    private DocumentCategory findCategory(Long categoryId) {
        return documentCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_CATEGORY_NOT_FOUND));
    }

    private void requireAdmin(AuthenticatedMember loginMember) {
        if (loginMember == null || !loginMember.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_PERMISSION_REQUIRED);
        }
    }

    /**
     * 카테고리 목록 조회용 스코프 가드(S15P11B106-199).
     * 접근 불가한 공개 범위는 존재를 숨기기 위해 WIKI_SCOPE_NOT_FOUND(404)로 처리한다.
     */
    private void requireReadableScope(AuthenticatedMember loginMember, String scopeKey) {
        if (!canReadScope(loginMember, scopeKey)) {
            throw new BusinessException(ErrorCode.WIKI_SCOPE_NOT_FOUND);
        }
    }

    private boolean canReadScope(AuthenticatedMember loginMember, String scopeKey) {
        if (loginMember == null) {
            return false;
        }
        if (loginMember.isAdmin()) {
            // 최고관리자=모든 범위, 부서관리자=담당 부서 단일 범위만.
            return departmentScopePolicy.resolve(loginMember.memberId()).canAccessScopeKey(scopeKey);
        }
        // 사원: 전체(ALL) 또는 본인 소속 부서를 포함하는 부서 공개 범위만.
        if ("ALL".equals(scopeKey)) {
            return true;
        }
        try {
            return ScopeKey.parse(scopeKey).departmentIds().contains(memberDepartmentId(loginMember.memberId()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 카테고리 생성/수정/삭제용 스코프 가드(관리자 전용, S15P11B106-199).
     * 부서관리자는 담당 부서 단일 범위만 관리할 수 있고, 그 외 범위는 {@code denyCode}로 거절한다
     * (생성=FORBIDDEN, 수정·삭제=DOCUMENT_CATEGORY_NOT_FOUND로 존재 숨김). 최고관리자는 제한이 없다.
     *
     * <p>전체 공개(ALL)만 여기서 제외한다(S15P11B106-289). 부서관리자가 전체 공개 문서를 <b>다루는</b>
     * 것과 전사 카테고리 체계를 <b>바꾸는</b> 것은 다른 무게다 — 전사 분류는 모든 부서가 함께 쓰므로
     * 최고관리자만 만들고 고친다. 조회(canReadScope)는 넓혀서 전체 공개 카테고리를 골라 문서를
     * 올릴 수 있다.
     *
     * <p>부서 범위는 담당 부서가 포함되면 관리할 수 있다 — "개발부+인사부"(D2-D5) 공간의 카테고리는
     * 그 두 부서장이 함께 관리한다.
     */
    private void requireManageableScope(AuthenticatedMember loginMember, String scopeKey, ErrorCode denyCode) {
        ScopeAccess access = departmentScopePolicy.resolve(loginMember.memberId());
        boolean manageable = access.isSuperAdmin()
                || (!"ALL".equals(scopeKey) && access.canAccessScopeKey(scopeKey));
        if (!manageable) {
            throw new BusinessException(denyCode);
        }
    }

    private Long memberDepartmentId(long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND))
                .getDepartment().getId();
    }

    private String normalizeScopeKey(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "scopeKey를 입력해주세요.");
        }
        return scopeKey.trim();
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "카테고리명을 입력해주세요.");
        }
        return name.trim();
    }

    private void requireExistingScope(String scopeKey) {
        if (!wikiScopeRepository.existsById(scopeKey)) {
            throw new BusinessException(ErrorCode.WIKI_SCOPE_NOT_FOUND);
        }
    }

    private void ensureScope(String scopeKey) {
        if (!wikiScopeRepository.existsById(scopeKey)) {
            wikiScopeRepository.save(wikiScopeFrom(scopeKey));
        }
    }

    private WikiScope wikiScopeFrom(String scopeKey) {
        // scope_key 파싱·검증(형식·정규화)은 ScopeKey.parse 단일 출처를 재사용한다.
        try {
            ScopeKey parsed = ScopeKey.parse(scopeKey);
            return parsed.isAll() ? WikiScope.all() : WikiScope.department(parsed.departmentIds());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_SCOPE_KEY);
        }
    }

    private void validateDuplicateName(String scopeKey, String name) {
        if (documentCategoryRepository.existsByScopeKeyAndName(scopeKey, name)) {
            throw new BusinessException(ErrorCode.DOCUMENT_CATEGORY_NAME_DUPLICATED);
        }
    }

    private void validateDuplicateName(String scopeKey, String name, Long categoryId) {
        if (documentCategoryRepository.existsByScopeKeyAndNameAndIdNot(scopeKey, name, categoryId)) {
            throw new BusinessException(ErrorCode.DOCUMENT_CATEGORY_NAME_DUPLICATED);
        }
    }
}
