package com.ajt.backend.domain.wiki.api;

import com.ajt.backend.domain.wiki.service.WikiQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작업(WIKI-01): Wiki 목록·검색 API.
 * 역할별 노출 범위·필터·페이지네이션은 서비스가 담당한다.
 */
@RestController
public class WikiQueryController {

    private final WikiQueryService wikiQueryService;

    public WikiQueryController(WikiQueryService wikiQueryService) {
        this.wikiQueryService = wikiQueryService;
    }

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
}
