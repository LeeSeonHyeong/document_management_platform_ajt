package com.ajt.backend.domain.document.api;

import com.ajt.backend.domain.document.service.DocumentCategoryService;
import com.ajt.backend.global.auth.AuthenticatedMember;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DocumentCategoryController {

    private final DocumentCategoryService documentCategoryService;

    /**
     * GET /api/v1/document-categories
     * scopeKey를 주면 그 Wiki 공간의 문서 카테고리만 조회한다(업로드 화면 등).
     * scopeKey를 생략하면 로그인 관리자가 접근 가능한 모든 공개 범위의 카테고리를 반환한다
     * (카테고리 관리 화면의 부서별 조회, S15P11B106-290).
     */
    @GetMapping("/api/v1/document-categories")
    public DocumentCategoryListResponse categories(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @RequestParam(required = false) String scopeKey
    ) {
        if (scopeKey == null || scopeKey.isBlank()) {
            return documentCategoryService.findAccessibleCategories(loginMember);
        }
        return documentCategoryService.findCategories(loginMember, scopeKey);
    }

    /**
     * POST /api/v1/document-categories
     * 관리자가 Wiki 공간별 문서 카테고리를 새로 생성합니다.
     */
    @PostMapping("/api/v1/document-categories")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentCategoryResponse createCategory(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @Valid @RequestBody DocumentCategoryCreateRequest request
    ) {
        return documentCategoryService.createCategory(loginMember, request);
    }

    /**
     * PATCH /api/v1/document-categories/{categoryId}
     * 관리자가 카테고리 이름이나 설명을 수정합니다.
     */
    @PatchMapping("/api/v1/document-categories/{categoryId}")
    public DocumentCategoryResponse updateCategory(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable Long categoryId,
            @Valid @RequestBody DocumentCategoryUpdateRequest request
    ) {
        return documentCategoryService.updateCategory(loginMember, categoryId, request);
    }

    /**
     * DELETE /api/v1/document-categories/{categoryId}
     * 관리자가 아직 문서에서 사용하지 않는 카테고리를 삭제합니다.
     */
    @DeleteMapping("/api/v1/document-categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable Long categoryId
    ) {
        documentCategoryService.deleteCategory(loginMember, categoryId);
    }
}
