package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.service.CurrentMember;
import com.ajt.backend.domain.document.service.CurrentMemberProvider;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.wiki.api.WikiChatMessageListResponse;
import com.ajt.backend.domain.wiki.api.WikiChatMessageResponse;
import com.ajt.backend.domain.wiki.api.WikiChatReplyResponse;
import com.ajt.backend.domain.wiki.api.WikiDetailResponse;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.model.WikiChatMessage;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiChatMessageRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.WikiEditRequest;
import com.ajt.backend.global.ai.client.WikiEditResponse;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 Wiki 상세 화면에서 AI 에이전트에게 수정을 지시하는 흐름입니다.
 *
 * <p>지시를 FastAPI {@code POST /internal/v1/wiki-edits}로 보내고, 돌아온 변경안을 Spring Boot가 검증해
 * 별도 승인 없이 현재 Wiki에 반영합니다. 관리자 지시와 에이전트 응답은 각각 대화로 저장합니다.
 */
@Service
public class WikiChatMessageService {

    private static final List<AiJobStatus> UNFINISHED_JOB_STATUSES =
            List.of(AiJobStatus.WAITING, AiJobStatus.PROCESSING);

    private final CurrentMemberProvider currentMemberProvider;
    private final WikiRepository wikiRepository;
    private final WikiCategoryRepository wikiCategoryRepository;
    private final WikiChatMessageRepository wikiChatMessageRepository;
    private final WikiFileStorage wikiFileStorage;
    private final DocumentRepository documentRepository;
    private final DocumentFileStorage documentFileStorage;
    private final AiJobRepository aiJobRepository;
    private final AiClient aiClient;
    private final WikiTransformationApplier applier;

    public WikiChatMessageService(
            CurrentMemberProvider currentMemberProvider,
            WikiRepository wikiRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiChatMessageRepository wikiChatMessageRepository,
            WikiFileStorage wikiFileStorage,
            DocumentRepository documentRepository,
            DocumentFileStorage documentFileStorage,
            AiJobRepository aiJobRepository,
            AiClient aiClient,
            WikiTransformationApplier applier
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.wikiRepository = wikiRepository;
        this.wikiCategoryRepository = wikiCategoryRepository;
        this.wikiChatMessageRepository = wikiChatMessageRepository;
        this.wikiFileStorage = wikiFileStorage;
        this.documentRepository = documentRepository;
        this.documentFileStorage = documentFileStorage;
        this.aiJobRepository = aiJobRepository;
        this.aiClient = aiClient;
        this.applier = applier;
    }

    @Transactional(readOnly = true)
    public WikiChatMessageListResponse getChatMessages(long wikiId) {
        requireAdmin();
        Wiki wiki = findWiki(wikiId);
        return new WikiChatMessageListResponse(
                wikiChatMessageRepository.findAllByWikiIdOrderByCreatedAtAscIdAsc(wiki.id())
                        .stream()
                        .map(WikiChatMessageResponse::from)
                        .toList()
        );
    }

    @Transactional
    public WikiChatReplyResponse sendChatMessage(long wikiId, String content) {
        CurrentMember currentMember = requireAdmin();
        Wiki wiki = findWiki(wikiId);
        String instruction = requireContent(content);
        requireNoUnfinishedJob(wiki.scopeKey());

        List<WikiChatMessage> history =
                wikiChatMessageRepository.findAllByWikiIdOrderByCreatedAtAscIdAsc(wiki.id());
        WikiEditResponse response = requestEdit(wiki, instruction, history);

        WikiChatMessage adminMessage = wikiChatMessageRepository.save(
                WikiChatMessage.fromAdmin(wiki, currentMember.memberId(), instruction)
        );
        applier.apply(wiki.scopeKey(), response);
        // 수정 반영으로 제목이 바뀔 수 있으므로 에이전트 메시지는 반영 뒤에 만든다.
        WikiChatMessage agentMessage = wikiChatMessageRepository.save(
                WikiChatMessage.fromAgent(wiki, response.agentMessage())
        );

        return new WikiChatReplyResponse(
                WikiChatMessageResponse.from(adminMessage),
                WikiChatMessageResponse.from(agentMessage),
                toDetail(wiki)
        );
    }

    private WikiEditResponse requestEdit(Wiki wiki, String instruction, List<WikiChatMessage> history) {
        try {
            return aiClient.editWiki(new WikiEditRequest(
                    String.valueOf(wiki.id()),
                    wiki.scopeKey(),
                    instruction,
                    new WikiEditRequest.WikiBody(wiki.title(), requireWikiContent(wiki)),
                    evidenceDocuments(wiki),
                    chatHistory(history)
            ));
        } catch (AiClientException exception) {
            throw new BusinessException(ErrorCode.WIKI_EDIT_FAILED);
        }
    }

    /**
     * 이 Wiki에 연결된 원본문서의 파싱 결과입니다.
     * 계약상 필수 필드를 채울 수 없는 문서(파싱 전이거나 본문이 빈 경우)는 전달하지 않습니다.
     */
    private List<WikiEditRequest.EvidenceDocument> evidenceDocuments(Wiki wiki) {
        List<Long> documentRefs = wiki.documentRefs();
        if (documentRefs.isEmpty()) {
            return List.of();
        }
        List<WikiEditRequest.EvidenceDocument> evidenceDocuments = new ArrayList<>();
        for (Document document : documentRepository.findAllById(documentRefs)) {
            if (document.parsedPath() == null || document.parsedPath().isBlank()) {
                continue;
            }
            String parsedMarkdown = readText(document.parsedPath());
            if (parsedMarkdown.isBlank()) {
                continue;
            }
            evidenceDocuments.add(new WikiEditRequest.EvidenceDocument(
                    String.valueOf(document.id()),
                    document.originalFileName(),
                    parsedMarkdown
            ));
        }
        return evidenceDocuments;
    }

    private List<WikiEditRequest.ChatMessage> chatHistory(List<WikiChatMessage> history) {
        return history.stream()
                .map(message -> new WikiEditRequest.ChatMessage(
                        message.senderType().apiValue(),
                        message.content()
                ))
                .toList();
    }

    private WikiDetailResponse toDetail(Wiki wiki) {
        return new WikiDetailResponse(
                String.valueOf(wiki.id()),
                wiki.title(),
                readWikiContent(wiki),
                category(wiki.wikiCategoryId()),
                wiki.scopeKey(),
                evidenceDocumentSummaries(wiki.documentRefs()),
                relatedWikis(wiki.scopeKey(), wiki.wikiRefs()),
                wiki.updatedAt()
        );
    }

    private WikiDetailResponse.Category category(long wikiCategoryId) {
        return wikiCategoryRepository.findById(wikiCategoryId)
                .map(category -> new WikiDetailResponse.Category(
                        String.valueOf(category.id()),
                        category.name()
                ))
                .orElse(null);
    }

    private List<WikiDetailResponse.EvidenceDocument> evidenceDocumentSummaries(List<Long> documentRefs) {
        if (documentRefs.isEmpty()) {
            return List.of();
        }
        Map<Long, Document> documentsById = new LinkedHashMap<>();
        documentRepository.findAllById(documentRefs)
                .forEach(document -> documentsById.put(document.id(), document));
        return documentRefs.stream()
                .map(documentsById::get)
                .filter(document -> document != null)
                .map(document -> WikiDetailResponse.EvidenceDocument.of(
                        document.id(),
                        document.originalFileName()
                ))
                .toList();
    }

    private List<WikiDetailResponse.RelatedWiki> relatedWikis(String scopeKey, List<Long> wikiRefs) {
        if (wikiRefs.isEmpty()) {
            return List.of();
        }
        Map<Long, Wiki> wikisById = new LinkedHashMap<>();
        wikiRepository.findAllByScopeKeyAndIdIn(scopeKey, wikiRefs)
                .forEach(related -> wikisById.put(related.id(), related));
        return wikiRefs.stream()
                .map(wikisById::get)
                .filter(related -> related != null)
                .map(related -> new WikiDetailResponse.RelatedWiki(
                        String.valueOf(related.id()),
                        related.title()
                ))
                .toList();
    }

    private CurrentMember requireAdmin() {
        CurrentMember currentMember = currentMemberProvider.currentMember();
        if (!currentMember.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_PERMISSION_REQUIRED);
        }
        return currentMember;
    }

    private Wiki findWiki(long wikiId) {
        return wikiRepository.findById(wikiId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_NOT_FOUND));
    }

    private void requireNoUnfinishedJob(String scopeKey) {
        if (aiJobRepository.existsByScopeKeyAndStatusIn(scopeKey, UNFINISHED_JOB_STATUSES)) {
            throw new BusinessException(ErrorCode.WIKI_EDIT_IN_PROGRESS);
        }
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.EMPTY_CHAT_CONTENT);
        }
        return content.trim();
    }

    /**
     * FastAPI 요청의 currentWiki.contentMarkdown은 비어 있을 수 없습니다.
     */
    private String requireWikiContent(Wiki wiki) {
        String contentMarkdown = readWikiContent(wiki);
        if (contentMarkdown.isBlank()) {
            throw new BusinessException(ErrorCode.WIKI_EDIT_FAILED);
        }
        return contentMarkdown;
    }

    private String readWikiContent(Wiki wiki) {
        if (wiki.wikiPath() == null || wiki.wikiPath().isBlank()) {
            return "";
        }
        try {
            return wikiFileStorage.readWikiMarkdown(wiki.wikiPath());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String readText(String storedPath) {
        try {
            return documentFileStorage.readText(storedPath);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
