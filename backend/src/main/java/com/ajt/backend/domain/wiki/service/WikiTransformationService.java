package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.WikiDocumentChangeType;
import com.ajt.backend.global.ai.client.WikiTransformationRequest;
import com.ajt.backend.global.ai.client.WikiTransformationResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    private final WikiTransformationApplier applier;

    public WikiTransformationService(
            AiClient aiClient,
            WikiRepository wikiRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiFileStorage wikiFileStorage,
            WikiTransformationApplier applier
    ) {
        this.aiClient = aiClient;
        this.wikiRepository = wikiRepository;
        this.wikiCategoryRepository = wikiCategoryRepository;
        this.wikiFileStorage = wikiFileStorage;
        this.applier = applier;
    }

    /**
     * 문서 추가에 대한 Wiki 변환을 수행하고 생성·수정된 Wiki ID를 반환합니다.
     *
     * <p>별도 트랜잭션으로 실행합니다. 변환 반영이 중간에 실패하면 Wiki 변경만 롤백하고,
     * 호출한 파싱 작업은 해당 문서를 실패로 기록한 뒤 다음 문서를 계속 처리해야 하기 때문입니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> transformForAddedDocument(
            long jobId,
            long documentId,
            String scopeKey,
            String parsedMarkdown,
            List<Long> selectedWikiIds
    ) {
        String currentIndex = currentIndex(scopeKey);
        WikiTransformationResponse response = aiClient.transformWiki(new WikiTransformationRequest(
                String.valueOf(jobId),
                String.valueOf(documentId),
                scopeKey,
                WikiDocumentChangeType.DOCUMENT_ADDED,
                parsedMarkdown,
                null,
                currentIndex,
                currentCategories(scopeKey),
                selectedWikis(scopeKey, selectedWikiIds, currentIndex)
        ));
        return applier.apply(scopeKey, documentId, response);
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
            List<Long> selectedWikiIds,
            String currentIndex
    ) {
        if (selectedWikiIds.isEmpty()) {
            return List.of();
        }
        WikiIndex index = WikiIndex.parse(currentIndex);
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
                    summaryOf(index, wiki),
                    contentMarkdown,
                    toStrings(wiki.documentRefs()),
                    toStrings(wiki.wikiRefs())
            ));
        }
        return selectedWikis;
    }

    /**
     * 계약은 요약을 필수로 요구하지만 wiki 테이블에 summary 컬럼이 없어 목차에서 되읽습니다.
     * 목차에 요약이 없으면 제목으로 대체합니다. (Wiki 엔티티의 TODO(DB) 참고)
     */
    private String summaryOf(WikiIndex index, Wiki wiki) {
        String summary = index.summaryOf(wiki.id());
        return summary == null ? wiki.title() : summary;
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
