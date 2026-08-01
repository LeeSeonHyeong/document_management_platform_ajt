package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.global.ai.capability.WikiCapabilityService;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.WikiDocumentChangeType;
import com.ajt.backend.global.ai.client.WikiTransformationRequest;
import com.ajt.backend.global.ai.client.WikiTransformationResponse;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * FastAPI Wiki 변환 API(POST /internal/v1/wiki-transformations) 호출과 결과 반영을 담당합니다.
 *
 * <p>수정(S15P11B106-174): <b>문맥을 밀어 보내지 않는다.</b> 현재 목차·카테고리와 선택된 Wiki
 * 본문을 싣던 것을 걷어냈다 — 에이전트가 Wiki 조회 API로 직접 읽는다. 이 서비스가 보내는 것은
 * 작업 번호·파싱 본문과 <b>조회 권한</b>(허가값·범위 버전)뿐이다.
 */
@Service
public class WikiTransformationService {

    private final AiClient aiClient;
    private final WikiScopeRepository wikiScopeRepository;
    private final WikiCapabilityService wikiCapabilityService;

    public WikiTransformationService(
            AiClient aiClient,
            WikiScopeRepository wikiScopeRepository,
            WikiCapabilityService wikiCapabilityService
    ) {
        this.aiClient = aiClient;
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
            String parsedMarkdown
    ) {
        return requestForDocumentChange(
                jobId,
                documentId,
                scopeKey,
                WikiDocumentChangeType.DOCUMENT_ADDED,
                parsedMarkdown,
                null
        );
    }

    /**
     * 문서 변경 종류에 따른 Wiki 변환을 FastAPI에 요청합니다.
     *
     * <p>{@code DOCUMENT_REMOVED}는 이 범위에서 문서가 빠졌다는 뜻이므로 새 파싱 본문 대신
     * {@code removedParsedMarkdown}으로 이 문서를 근거로 쓴 Wiki를 걷어내게 한다. 계약이 이
     * 본문을 필수로 요구하므로 호출자가 파일을 옮기거나 지우기 전에 읽어 두어야 한다.
     *
     * <p>{@code scopeKey}는 문서의 현재 범위가 아니라 <b>작업의 범위</b>다. 범위 변경 재처리에서
     * 문서 행은 이미 새 범위로 옮겨져 있어, 문서 기준으로 잡으면 걷어낼 옛 범위를 찾지 못한다.
     */
    public WikiTransformationResponse requestForDocumentChange(
            long jobId,
            long documentId,
            String scopeKey,
            WikiDocumentChangeType changeType,
            String parsedMarkdown,
            String removedParsedMarkdown
    ) {
        long scopeVersion = wikiScopeRepository.findById(scopeKey).orElseThrow().scopeVersion();
        String capability = wikiCapabilityService.issue(scopeKey, scopeVersion, Duration.ofMinutes(30));
        try {
            return aiClient.transformWiki(new WikiTransformationRequest(
                    String.valueOf(jobId),
                    String.valueOf(documentId),
                    scopeKey,
                    changeType,
                    parsedMarkdown,
                    removedParsedMarkdown,
                    capability,
                    scopeVersion
            ));
        } finally {
            wikiCapabilityService.revoke(capability);
        }
    }

}
