package com.ajt.backend.domain.wiki.api;

import com.ajt.backend.domain.wiki.service.WikiQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wiki 조회 API입니다.
 * Wiki 목록·검색과 함께 Wiki 공간 목록, 공간별 카테고리 목록, Wiki 상세를 접근 권한에 맞게 반환합니다.
 * 역할별 노출 범위·필터·페이지네이션은 서비스가 담당한다.
 */
@RestController
public class WikiQueryController {

    private final WikiQueryService wikiQueryService;

    public WikiQueryController(WikiQueryService wikiQueryService) {
        this.wikiQueryService = wikiQueryService;
    }

    /**
     * GET /api/v1/wiki-spaces
     * 로그인 사용자가 접근 가능한 독립 Wiki 공간 목록을 조회합니다.
     */
    @GetMapping("/api/v1/wiki-spaces")
    public WikiSpaceListResponse wikiSpaces() {
        return wikiQueryService.findAccessibleSpaces();
    }

    /**
     * GET /api/v1/wiki-categories?scopeKey=...
     * 특정 Wiki 공간에서 AI가 관리하는 카테고리 목록을 조회합니다.
     */
    @GetMapping("/api/v1/wiki-categories")
    public WikiCategoryListResponse wikiCategories(
            @RequestParam(name = "scopeKey", required = false) String scopeKey
    ) {
        return wikiQueryService.findCategories(scopeKey);
    }

    /**
     * GET /api/v1/wikis
     * 로그인 사용자가 조회 가능한 Wiki 목록을 필터·페이지네이션과 함께 조회합니다.
     */
    @GetMapping("/api/v1/wikis")
    public WikiListResponse listWikis(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "scopeKey", required = false) String scopeKey,
            @RequestParam(name = "wikiCategoryId", required = false) Long wikiCategoryId,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "sort", required = false) String sort
    ) {
        return wikiQueryService.findWikis(page, size, scopeKey, wikiCategoryId, keyword, sort);
    }

    /**
     * GET /api/v1/wikis/{wikiId}
     * Wiki 본문, 연결 원본문서와 연관 Wiki를 조회합니다.
     */
    @GetMapping("/api/v1/wikis/{wikiId}")
    public WikiDetailResponse wikiDetail(@PathVariable long wikiId) {
        return wikiQueryService.getWiki(wikiId);
    }
}
