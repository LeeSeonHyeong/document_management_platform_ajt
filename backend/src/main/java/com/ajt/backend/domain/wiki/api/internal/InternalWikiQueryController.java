package com.ajt.backend.domain.wiki.api.internal;

import com.ajt.backend.domain.wiki.service.InternalWikiQueryService;
import com.ajt.backend.global.ai.capability.WikiCapabilityService;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalWikiQueryController {
    private final InternalWikiQueryService queryService;
    private final WikiCapabilityService capabilityService;
    public InternalWikiQueryController(InternalWikiQueryService queryService, WikiCapabilityService capabilityService) {
        this.queryService = queryService; this.capabilityService = capabilityService;
    }
    private void authorize(String capability, String scopeKey) { capabilityService.require(capability, scopeKey); }
    private int limit(int value, int maximum) {
        if (value < 1 || value > maximum) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return value;
    }
    @GetMapping("/internal/v1/wiki-search")
    public InternalWikiQueryService.WikiSearch search(@RequestParam String scopeKey, @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit,
            @RequestHeader(value = "X-Wiki-Capability", required = false) String capability) {
        if (query == null || query.isBlank()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        authorize(capability, scopeKey); return queryService.search(scopeKey, query, limit(limit, 50));
    }
    @GetMapping("/internal/v1/wiki-pages")
    public InternalWikiQueryService.WikiPages pages(@RequestParam String scopeKey, @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String cursor,
            @RequestHeader(value = "X-Wiki-Capability", required = false) String capability) {
        authorize(capability, scopeKey); return queryService.pages(scopeKey, limit(limit, 500), cursor);
    }
    @GetMapping("/internal/v1/wikis/{wikiId}/content")
    public InternalWikiQueryService.WikiContent content(@PathVariable long wikiId, @RequestParam String scopeKey,
            @RequestHeader(value = "X-Wiki-Capability", required = false) String capability) { authorize(capability, scopeKey); return queryService.content(scopeKey, wikiId); }
    @GetMapping("/internal/v1/wikis/{wikiId}/relations")
    public InternalWikiQueryService.WikiRelations relations(@PathVariable long wikiId, @RequestParam String scopeKey,
            @RequestHeader(value = "X-Wiki-Capability", required = false) String capability) { authorize(capability, scopeKey); return queryService.relations(scopeKey, wikiId); }
    @GetMapping("/internal/v1/wiki-spaces/{scopeKey}/index")
    public InternalWikiQueryService.WikiIndex index(@PathVariable String scopeKey, @RequestHeader(value = "X-Wiki-Capability", required = false) String capability) { authorize(capability, scopeKey); return queryService.index(scopeKey); }
    @GetMapping("/internal/v1/wiki-spaces/{scopeKey}/categories")
    public InternalWikiQueryService.WikiCategories categories(@PathVariable String scopeKey, @RequestHeader(value = "X-Wiki-Capability", required = false) String capability) { authorize(capability, scopeKey); return queryService.categories(scopeKey); }
    @GetMapping("/internal/v1/wiki-spaces/{scopeKey}/relations")
    public InternalWikiQueryService.WikiSpaceRelations spaceRelations(@PathVariable String scopeKey,
            @RequestHeader(value = "X-Wiki-Capability", required = false) String capability) { authorize(capability, scopeKey); return queryService.spaceRelations(scopeKey); }
    @GetMapping("/internal/v1/documents/{documentId}/parsed")
    public InternalWikiQueryService.ParsedDocument parsed(@PathVariable long documentId, @RequestParam String scopeKey,
            @RequestHeader(value = "X-Wiki-Capability", required = false) String capability) { authorize(capability, scopeKey); return queryService.parsedDocument(scopeKey, documentId); }
}
