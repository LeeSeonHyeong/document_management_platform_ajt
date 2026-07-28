package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.document.api.DocumentCategoryCreateRequest;
import com.ajt.backend.domain.document.api.DocumentCategoryListResponse;
import com.ajt.backend.domain.document.api.DocumentCategoryResponse;
import com.ajt.backend.domain.document.api.DocumentCategoryUpdateRequest;
import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentCategoryService {

    private final DocumentCategoryRepository documentCategoryRepository;
    private final DocumentRepository documentRepository;
    private final WikiScopeRepository wikiScopeRepository;

    /**
     * CAT-01 문서 카테고리 목록 조회 기능입니다.
     * 같은 Wiki 공간(scopeKey)에 속한 카테고리만 이름순으로 보여줍니다.
     */
    @Transactional(readOnly = true)
    public DocumentCategoryListResponse findCategories(String scopeKey) {
        String normalizedScopeKey = normalizeScopeKey(scopeKey);
        requireExistingScope(normalizedScopeKey);

        return DocumentCategoryListResponse.from(
                documentCategoryRepository.findAllByScopeKeyOrderByNameAsc(normalizedScopeKey)
        );
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
        if ("ALL".equals(scopeKey)) {
            return WikiScope.all();
        }
        if (!scopeKey.matches("D\\d+(?:-D\\d+)*")) {
            throw new BusinessException(ErrorCode.INVALID_SCOPE_KEY);
        }

        try {
            List<Long> departmentIds = Arrays.stream(scopeKey.split("-"))
                    .map(value -> value.substring(1))
                    .map(Long::valueOf)
                    .toList();
            WikiScope wikiScope = WikiScope.department(departmentIds);
            if (!wikiScope.scopeKey().equals(scopeKey)) {
                throw new BusinessException(ErrorCode.INVALID_SCOPE_KEY);
            }
            return wikiScope;
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
