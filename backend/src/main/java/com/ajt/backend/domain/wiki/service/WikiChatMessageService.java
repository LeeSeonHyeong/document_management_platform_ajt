package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.service.CurrentMember;
import com.ajt.backend.domain.document.service.CurrentMemberProvider;
import com.ajt.backend.domain.member.DepartmentScopePolicy;
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
import com.ajt.backend.global.ai.capability.WikiCapabilityService;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 Wiki 상세 화면에서 AI 에이전트에게 수정을 지시하는 흐름입니다.
 *
 * <p>지시를 FastAPI {@code POST /internal/v1/wiki-edits}로 보내고, 돌아온 변경안을 Spring Boot가 검증해
 * 별도 승인 없이 현재 Wiki에 반영합니다. 관리자 지시와 에이전트 응답은 각각 대화로 저장합니다.
 *
 * <p>수정(S15P11B106-176): <b>본문을 밀어 보내지 않는다.</b> 수정 대상 Wiki 본문과 근거 원본문서를
 * 싣던 것을 걷어냈다 — 에이전트가 Wiki 조회 API로 직접 읽는다. 이 서비스가 보내는 것은 대상 ID·지시·
 * 대화 이력, <b>조회 권한</b>(허가값·범위 버전), 관리자 지시 원본문서 ID뿐이다.
 */
@Service
public class WikiChatMessageService {

    private static final List<AiJobStatus> UNFINISHED_JOB_STATUSES =
            List.of(AiJobStatus.WAITING, AiJobStatus.PROCESSING);

    private final CurrentMemberProvider currentMemberProvider;
    private final AdminInstructionDocumentService adminInstructionDocumentService;
    private final WikiRepository wikiRepository;
    private final WikiCategoryRepository wikiCategoryRepository;
    private final WikiChatMessageRepository wikiChatMessageRepository;
    private final WikiFileStorage wikiFileStorage;
    private final DocumentRepository documentRepository;
    private final AiJobRepository aiJobRepository;
    private final AiClient aiClient;
    private final WikiTransformationApplier applier;
    private final WikiScopeRepository wikiScopeRepository;
    private final WikiCapabilityService wikiCapabilityService;
    private final DepartmentScopePolicy departmentScopePolicy;

    public WikiChatMessageService(
            CurrentMemberProvider currentMemberProvider,
            AdminInstructionDocumentService adminInstructionDocumentService,
            WikiRepository wikiRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiChatMessageRepository wikiChatMessageRepository,
            WikiFileStorage wikiFileStorage,
            DocumentRepository documentRepository,
            AiJobRepository aiJobRepository,
            AiClient aiClient,
            WikiTransformationApplier applier,
            WikiScopeRepository wikiScopeRepository,
            WikiCapabilityService wikiCapabilityService,
            DepartmentScopePolicy departmentScopePolicy
    ) {
        this.currentMemberProvider = currentMemberProvider;
        this.adminInstructionDocumentService = adminInstructionDocumentService;
        this.wikiRepository = wikiRepository;
        this.wikiCategoryRepository = wikiCategoryRepository;
        this.wikiChatMessageRepository = wikiChatMessageRepository;
        this.wikiFileStorage = wikiFileStorage;
        this.documentRepository = documentRepository;
        this.aiJobRepository = aiJobRepository;
        this.aiClient = aiClient;
        this.applier = applier;
        this.wikiScopeRepository = wikiScopeRepository;
        this.wikiCapabilityService = wikiCapabilityService;
        this.departmentScopePolicy = departmentScopePolicy;
    }

    @Transactional(readOnly = true)
    public WikiChatMessageListResponse getChatMessages(long wikiId) {
        CurrentMember currentMember = requireAdmin();
        Wiki wiki = findWiki(wikiId);
        requireWikiScope(currentMember, wiki);
        return new WikiChatMessageListResponse(
                chatHistoryForScope(wiki.scopeKey())
                        .stream()
                        .map(WikiChatMessageResponse::from)
                        .toList()
        );
    }

    @Transactional
    public WikiChatReplyResponse sendChatMessage(long wikiId, String content) {
        CurrentMember currentMember = requireAdmin();
        Wiki wiki = findWiki(wikiId);
        requireWikiScope(currentMember, wiki);
        String instruction = requireContent(content);
        requireNoUnfinishedJob(wiki.scopeKey());

        long adminInstructionDocumentId = adminInstructionDocumentService.create(
                wiki.id(), currentMember.memberId(), instruction);
        // create()는 REQUIRES_NEW라 바깥 영속성 컨텍스트의 wiki에는 커밋된 document_refs가
        // 보이지 않는다. 응답 누락과 이후 dirty flush의 덮어쓰기를 모두 막도록 같은 ID를
        // 바깥 관리 엔티티에도 멱등하게 합친다.
        wiki.addDocumentRefs(List.of(adminInstructionDocumentId));
        List<WikiChatMessage> history = chatHistoryForScope(wiki.scopeKey());
        WikiEditResponse response = requestEdit(wiki, instruction, history, adminInstructionDocumentId);
        requireAdminInstructionEvidence(response, adminInstructionDocumentId);

        WikiChatMessage adminMessage = wikiChatMessageRepository.save(
                WikiChatMessage.fromAdmin(wiki, currentMember.memberId(), instruction)
        );
        // 수정 반영으로 제목이 바뀔 수 있으므로 에이전트 메시지는 반영 뒤에 만든다.
        List<Long> affectedWikiIds = applier.apply(wiki.scopeKey(), response);
        WikiChatMessage agentMessage = wikiChatMessageRepository.save(
                WikiChatMessage.fromAgent(resolveAgentSubjectWiki(wiki, affectedWikiIds), response.agentMessage())
        );

        return new WikiChatReplyResponse(
                WikiChatMessageResponse.from(adminMessage),
                WikiChatMessageResponse.from(agentMessage),
                toDetail(wiki)
        );
    }

    /**
     * 에이전트 메시지를 어느 위키에 연결할지 정한다(S15P11B106-243).
     *
     * <p>{@code applier.apply(...)}가 돌려주는 목록은 이번 지시로 실제 생성·수정된(임시 ID 치환이
     * 끝난) 위키 ID다 — 관리자가 보던 위키가 아니라 **에이전트가 실제로 고친 위키**를 메시지의
     * {@code wiki_id}·{@code wiki_title_snapshot}으로 남겨야, 화면에서 그 메시지를 보고 어느
     * 위키가 바뀌었는지 바로 링크를 따라갈 수 있다.
     *
     * <p>{@code wiki_chat_message.wiki_id}는 위키 하나만 가리키는 단일 FK라 여러 위키가 한 번에
     * 바뀌어도 첫 번째만 연결 대상으로 삼는다 — 나머지는 에이전트의 텍스트 응답 자체에 이미
     * 나열돼 있어 완전히 못 찾는 것은 아니다. 변경이 하나도 없었으면(예: 이미 반영된 상태) 관리자가
     * 보던 위키로 되돌아간다.
     */
    private Wiki resolveAgentSubjectWiki(Wiki fallback, List<Long> affectedWikiIds) {
        if (affectedWikiIds.isEmpty()) {
            return fallback;
        }
        return wikiRepository.findById(affectedWikiIds.get(0)).orElse(fallback);
    }

    /**
     * 같은 scope(부서)의 위키를 넘나드는 대화 이력입니다(S15P11B106-220).
     *
     * <p>메시지는 지금도 위키(wiki_id) 단위로 저장된다(FR-AI-004) — 여기서는 조회만 그 scope에
     * 속한 위키 전체로 넓힌다. 위키 페이지를 옮겨 다녀도 같은 부서 안이면 한 대화로 이어진다.
     */
    private List<WikiChatMessage> chatHistoryForScope(String scopeKey) {
        List<Long> wikiIdsInScope = wikiRepository.findAllByScopeKey(scopeKey).stream()
                .map(Wiki::id)
                .toList();
        return wikiChatMessageRepository.findAllByWikiIdInOrderByCreatedAtAscIdAsc(wikiIdsInScope);
    }

    /**
     * 부서관리자 스코프 가드입니다(S15P11B106-199).
     * 부서관리자는 담당 부서 scope의 Wiki만 조회·수정 요청할 수 있고, 담당 밖(전체·타부서) Wiki는 존재를 숨겨
     * WIKI_NOT_FOUND로 처리한다. 최고관리자는 제한이 없다.
     */
    private void requireWikiScope(CurrentMember currentMember, Wiki wiki) {
        if (!departmentScopePolicy.resolve(currentMember.memberId()).canAccessScopeKey(wiki.scopeKey())) {
            throw new BusinessException(ErrorCode.WIKI_NOT_FOUND);
        }
    }

    private WikiEditResponse requestEdit(
            Wiki wiki,
            String instruction,
            List<WikiChatMessage> history,
            long adminInstructionDocumentId
    ) {
        long scopeVersion = wikiScopeRepository.findById(wiki.scopeKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_NOT_FOUND))
                .scopeVersion();
        String capability = wikiCapabilityService.issue(wiki.scopeKey(), scopeVersion, Duration.ofMinutes(30));
        try {
            return aiClient.editWiki(new WikiEditRequest(
                    String.valueOf(wiki.id()),
                    wiki.scopeKey(),
                    instruction,
                    chatHistory(history),
                    capability,
                    scopeVersion,
                    String.valueOf(adminInstructionDocumentId)
            ));
        } catch (AiClientException exception) {
            // AI 서버 미가동/연결 실패·타임아웃은 일시적 이용 불가 → 503. AI가 응답한 처리 실패는 기존 500 유지.
            if (exception.failureType().isServerUnavailable()) {
                throw new BusinessException(ErrorCode.AI_SERVER_UNAVAILABLE);
            }
            throw new BusinessException(ErrorCode.WIKI_EDIT_FAILED);
        } finally {
            wikiCapabilityService.revoke(capability);
        }
    }

    private void requireAdminInstructionEvidence(WikiEditResponse response, long documentId) {
        String requiredId = String.valueOf(documentId);
        boolean missing = response.wikiChanges().stream().anyMatch(change ->
                change.evidence().stream().noneMatch(evidence -> requiredId.equals(evidence.documentId())));
        if (missing) {
            throw new BusinessException(ErrorCode.WIKI_EDIT_FAILED);
        }
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
}
