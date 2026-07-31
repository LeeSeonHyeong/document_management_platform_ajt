package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.global.ai.capability.WikiCapabilityService;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.WikiDocumentChangeType;
import com.ajt.backend.global.ai.client.WikiTransformationRequest;
import com.ajt.backend.global.ai.client.WikiTransformationResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * FastAPI Wiki 변환 API(POST /internal/v1/wiki-transformations) 호출과 결과 반영을 담당합니다.
 *
 * <p>문맥 선택 API가 고른 Wiki ID는 Spring Boot가 같은 공간에 실제로 있는지 다시 확인한 뒤 본문까지 읽어 전달합니다.
 * 다른 scopeKey의 Wiki는 계약상 전달하지 않습니다.
 */
@Service
public class WikiTransformationService {

    private final AiClient aiClient;
    private final WikiRepository wikiRepository;
    private final WikiCategoryRepository wikiCategoryRepository;
    private final WikiFileStorage wikiFileStorage;
    private final WikiScopeRepository wikiScopeRepository;
    private final WikiCapabilityService wikiCapabilityService;

    public WikiTransformationService(
            AiClient aiClient,
            WikiRepository wikiRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiFileStorage wikiFileStorage,
            WikiScopeRepository wikiScopeRepository,
            WikiCapabilityService wikiCapabilityService
    ) {
        this.aiClient = aiClient;
        this.wikiRepository = wikiRepository;
        this.wikiCategoryRepository = wikiCategoryRepository;
        this.wikiFileStorage = wikiFileStorage;
        this.wikiScopeRepository = wikiScopeRepository;
        this.wikiCapabilityService = wikiCapabilityService;
    }

    /**
     * 문서 추가에 대한 Wiki 변환을 FastAPI에 요청합니다.
     *
     * <p>AI 호출은 장시간 걸릴 수 있어 트랜잭션 없이 수행합니다. 응답 반영은 호출자가 별도
     * 트랜잭션 서비스에 맡깁니다.
     */
    public WikiTransformationResponse requestForAddedDocument(
            long jobId,
            long documentId,
            String scopeKey,
            String parsedMarkdown,
            List<Long> selectedWikiIds
    ) {
        String currentIndex = currentIndex(scopeKey);
        long scopeVersion = wikiScopeRepository.findById(scopeKey).orElseThrow().scopeVersion();
        String capability = wikiCapabilityService.issue(scopeKey, scopeVersion, Duration.ofMinutes(30));
        try {
            return aiClient.transformWiki(new WikiTransformationRequest(
                    String.valueOf(jobId),
                    String.valueOf(documentId),
                    scopeKey,
                    WikiDocumentChangeType.DOCUMENT_ADDED,
                    parsedMarkdown,
                    null,
                    currentIndex,
                    currentCategories(scopeKey),
                    selectedWikis(scopeKey, selectedWikiIds),
                    capability,
                    scopeVersion
            ));
        } finally {
            wikiCapabilityService.revoke(capability);
        }
    }

    /**
     * 공간의 현재 목차입니다. 목차가 아직 없는 새 공간이면 빈 목차를 돌려줍니다.
     */
    public String currentIndex(String scopeKey) {
        try {
            return wikiFileStorage.readIndex(scopeKey);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private List<WikiTransformationRequest.CurrentCategory> currentCategories(String scopeKey) {
        return wikiCategoryRepository.findAllByScopeKeyOrderByNameAsc(scopeKey)
                .stream()
                .map(category -> new WikiTransformationRequest.CurrentCategory(
                        String.valueOf(category.id()),
                        category.name()
                ))
                .toList();
    }

    private List<WikiTransformationRequest.SelectedWiki> selectedWikis(
            String scopeKey,
            List<Long> selectedWikiIds
    ) {
        if (selectedWikiIds.isEmpty()) {
            return List.of();
        }
        List<WikiTransformationRequest.SelectedWiki> selectedWikis = new ArrayList<>();
        for (Wiki wiki : wikiRepository.findAllByScopeKeyAndIdIn(scopeKey, selectedWikiIds)) {
            String contentMarkdown = readWikiMarkdown(wiki.wikiPath());
            if (contentMarkdown.isBlank()) {
                // 본문 파일이 비었으면 계약의 필수 필드를 채울 수 없으므로 문맥에서 제외한다.
                continue;
            }
            selectedWikis.add(new WikiTransformationRequest.SelectedWiki(
                    String.valueOf(wiki.id()),
                    String.valueOf(wiki.wikiCategoryId()),
                    wiki.title(),
                    wiki.summary() == null ? wiki.title() : wiki.summary(),
                    wiki.wikiPath(),
                    contentMarkdown,
                    toStrings(wiki.documentRefs()),
                    toStrings(wiki.wikiRefs())
            ));
        }
        return selectedWikis;
    }

    private String readWikiMarkdown(String wikiPath) {
        if (wikiPath == null || wikiPath.isBlank()) {
            return "";
        }
        try {
            return wikiFileStorage.readWikiMarkdown(wikiPath);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> toStrings(List<Long> ids) {
        return ids.stream().map(String::valueOf).toList();
    }
}
