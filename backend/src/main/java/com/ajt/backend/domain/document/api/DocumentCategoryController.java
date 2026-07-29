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
     * 프론트가 선택한 Wiki 공간(scopeKey)에 맞는 문서 카테고리 목록을 조회합니다.
     */
    @GetMapping("/api/v1/document-categories")
    public DocumentCategoryListResponse categories(@RequestParam String scopeKey) {
        return documentCategoryService.findCategories(scopeKey);
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
