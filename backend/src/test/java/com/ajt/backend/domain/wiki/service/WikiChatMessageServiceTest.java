package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ajt.backend.domain.document.model.AiJobStatus;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.AiJobRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.service.CurrentMember;
import com.ajt.backend.domain.document.service.CurrentMemberProvider;
import com.ajt.backend.domain.document.service.CurrentMemberRole;
import com.ajt.backend.domain.member.DepartmentScopePolicy;
import com.ajt.backend.domain.member.ScopeAccess;
import com.ajt.backend.domain.wiki.api.WikiChatMessageListResponse;
import com.ajt.backend.domain.wiki.api.WikiChatReplyResponse;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.model.WikiChatMessage;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiChatMessageRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.AiClientFailureType;
import com.ajt.backend.global.ai.client.WikiEditRequest;
import com.ajt.backend.global.ai.client.WikiEditResponse;
import com.ajt.backend.global.ai.capability.WikiCapabilityService;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import com.ajt.backend.global.error.FieldErrorResponse;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Wiki 관리자 대화 서비스")
class WikiChatMessageServiceTest {

    private static final String SCOPE_KEY = "ALL";

    private final CurrentMemberProvider currentMemberProvider = mock(CurrentMemberProvider.class);
    private final WikiRepository wikiRepository = mock(WikiRepository.class);
    private final WikiCategoryRepository wikiCategoryRepository = mock(WikiCategoryRepository.class);
    private final WikiChatMessageRepository wikiChatMessageRepository = mock(WikiChatMessageRepository.class);
    private final WikiFileStorage wikiFileStorage = mock(WikiFileStorage.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final AiJobRepository aiJobRepository = mock(AiJobRepository.class);
    private final AiClient aiClient = mock(AiClient.class);
    private final WikiTransformationApplier applier = mock(WikiTransformationApplier.class);
    private final WikiScopeRepository wikiScopeRepository = mock(WikiScopeRepository.class);
    private final WikiCapabilityService wikiCapabilityService = mock(WikiCapabilityService.class);
    private final DepartmentScopePolicy departmentScopePolicy = superAdminScopePolicy();
    private final WikiChatMessageService service = new WikiChatMessageService(
            currentMemberProvider,
            wikiRepository,
            wikiCategoryRepository,
            wikiChatMessageRepository,
            wikiFileStorage,
            documentRepository,
            aiJobRepository,
            aiClient,
            applier,
            wikiScopeRepository,
            wikiCapabilityService,
            departmentScopePolicy
    );

    // 기존 테스트의 관리자는 전체 접근(최고관리자)으로 취급해 기존 동작을 유지한다(S15P11B106-199).
    private static DepartmentScopePolicy superAdminScopePolicy() {
        DepartmentScopePolicy policy = mock(DepartmentScopePolicy.class);
        given(policy.resolve(anyLong())).willReturn(ScopeAccess.superAdmin());
        return policy;
    }

    private final AtomicLong nextMessageId = new AtomicLong(1L);

    @BeforeEach
    void setUp() {
        given(wikiScopeRepository.findById(SCOPE_KEY)).willReturn(Optional.of(WikiScope.all()));
        given(wikiCapabilityService.issue(eq(SCOPE_KEY), eq(0L), any(Duration.class))).willReturn("capability");
        given(wikiChatMessageRepository.save(any(WikiChatMessage.class))).willAnswer(invocation -> {
            WikiChatMessage message = invocation.getArgument(0);
            assign(message, "id", nextMessageId.getAndIncrement());
            assign(message, "createdAt", java.time.Instant.parse("2026-07-29T09:00:00Z"));
            return message;
        });
    }

    @Test
    @DisplayName("관리자 지시를 FastAPI에 보내고 변경을 반영한 뒤 두 메시지를 저장한다")
    void sendsChatMessage() throws Exception {
        Wiki wiki = wiki(101L, 9L, "휴가 규정", List.of(15L), List.of(108L));
        Wiki related = wiki(108L, 9L, "근태 관리", List.of(), List.of());
        adminLoggedIn();
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        given(wikiFileStorage.readWikiMarkdown("wiki/ALL/pages/101.md")).willReturn("# 휴가 규정\n본문");
        given(wikiRepository.findAllByScopeKey(SCOPE_KEY)).willReturn(List.of(wiki));
        given(wikiChatMessageRepository.findAllByWikiIdInOrderByCreatedAtAscIdAsc(List.of(101L)))
                .willReturn(List.of(existingAgentMessage(wiki, "이전 응답입니다.")));
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of(document(15L, "취업규칙.pdf")));
        given(aiJobRepository.existsByScopeKeyAndStatusIn(anyString(), anyCollection())).willReturn(false);
        given(wikiCategoryRepository.findById(9L)).willReturn(Optional.of(category(9L, "휴가 및 근태")));
        given(wikiRepository.findAllByScopeKeyAndIdIn(SCOPE_KEY, List.of(108L))).willReturn(List.of(related));
        given(aiClient.editWiki(any(WikiEditRequest.class))).willReturn(new WikiEditResponse(
                "중복된 연차 항목을 정리했습니다.",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));

        WikiChatReplyResponse response = service.sendChatMessage(101L, "중복된 휴가 규정을 하나로 정리해줘.");

        assertThat(response.adminMessage().senderType()).isEqualTo("admin");
        assertThat(response.adminMessage().content()).isEqualTo("중복된 휴가 규정을 하나로 정리해줘.");
        assertThat(response.agentMessage().senderType()).isEqualTo("agent");
        assertThat(response.agentMessage().content()).isEqualTo("중복된 연차 항목을 정리했습니다.");
        assertThat(response.updatedWiki().wikiId()).isEqualTo("101");
        assertThat(response.updatedWiki().title()).isEqualTo("휴가 규정");
        assertThat(response.updatedWiki().contentMarkdown()).isEqualTo("# 휴가 규정\n본문");
        assertThat(response.updatedWiki().category().wikiCategoryId()).isEqualTo("9");
        assertThat(response.updatedWiki().category().name()).isEqualTo("휴가 및 근태");
        assertThat(response.updatedWiki().scopeKey()).isEqualTo(SCOPE_KEY);
        assertThat(response.updatedWiki().evidenceDocuments()).singleElement().satisfies(document -> {
            assertThat(document.documentId()).isEqualTo("15");
            assertThat(document.originalFileName()).isEqualTo("취업규칙.pdf");
            assertThat(document.downloadUrl()).isEqualTo("/api/v1/documents/15/file");
        });
        assertThat(response.updatedWiki().relatedWikis()).singleElement().satisfies(relatedWiki -> {
            assertThat(relatedWiki.wikiId()).isEqualTo("108");
            assertThat(relatedWiki.title()).isEqualTo("근태 관리");
        });
        verify(applier).apply(eq(SCOPE_KEY), any(WikiEditResponse.class));
    }

    @Test
    @DisplayName("FastAPI 요청에 기존 대화와 조회 권한만 계약대로 담는다")
    void buildsEditRequest() throws Exception {
        Wiki wiki = wiki(101L, 9L, "휴가 규정", List.of(15L), List.of());
        adminLoggedIn();
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        given(wikiFileStorage.readWikiMarkdown("wiki/ALL/pages/101.md")).willReturn("# 휴가 규정\n본문");
        given(wikiRepository.findAllByScopeKey(SCOPE_KEY)).willReturn(List.of(wiki));
        given(wikiChatMessageRepository.findAllByWikiIdInOrderByCreatedAtAscIdAsc(List.of(101L)))
                .willReturn(List.of(existingAgentMessage(wiki, "이전 응답입니다.")));
        given(documentRepository.findAllById(List.of(15L))).willReturn(List.of(document(15L, "취업규칙.pdf")));
        given(aiJobRepository.existsByScopeKeyAndStatusIn(anyString(), anyCollection())).willReturn(false);
        given(aiClient.editWiki(any(WikiEditRequest.class)))
                .willReturn(new WikiEditResponse("반영했습니다.", List.of(), List.of(), List.of(), List.of()));

        service.sendChatMessage(101L, "중복 규정을 정리해줘.");

        ArgumentCaptor<WikiEditRequest> captor = ArgumentCaptor.forClass(WikiEditRequest.class);
        verify(aiClient).editWiki(captor.capture());
        WikiEditRequest request = captor.getValue();
        assertThat(request.wikiId()).isEqualTo("101");
        assertThat(request.scopeKey()).isEqualTo(SCOPE_KEY);
        assertThat(request.instruction()).isEqualTo("중복 규정을 정리해줘.");
        assertThat(request.wikiCapability()).isEqualTo("capability");
        assertThat(request.scopeVersion()).isZero();
        assertThat(request.chatHistory()).singleElement().satisfies(message -> {
            assertThat(message.senderType()).isEqualTo("agent");
            assertThat(message.content()).isEqualTo("이전 응답입니다.");
        });
    }

    @Test
    @DisplayName("같은 공간에 끝나지 않은 AI 작업이 있으면 409로 거절한다")
    void rejectsWhenJobInProgress() throws Exception {
        Wiki wiki = wiki(101L, 9L, "휴가 규정", List.of(), List.of());
        adminLoggedIn();
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        given(wikiFileStorage.readWikiMarkdown("wiki/ALL/pages/101.md")).willReturn("# 휴가 규정");
        given(aiJobRepository.existsByScopeKeyAndStatusIn(
                SCOPE_KEY,
                List.of(AiJobStatus.WAITING, AiJobStatus.PROCESSING)
        )).willReturn(true);

        assertThatThrownBy(() -> service.sendChatMessage(101L, "정리해줘."))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_EDIT_IN_PROGRESS);
        verify(aiClient, never()).editWiki(any(WikiEditRequest.class));
    }

    @Test
    @DisplayName("내용이 비어 있으면 400으로 거절한다")
    void rejectsBlankContent() throws Exception {
        Wiki wiki = wiki(101L, 9L, "휴가 규정", List.of(), List.of());
        adminLoggedIn();
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));

        assertThatThrownBy(() -> service.sendChatMessage(101L, "   "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EMPTY_CHAT_CONTENT);
        verify(aiClient, never()).editWiki(any(WikiEditRequest.class));
    }

    @Test
    @DisplayName("존재하지 않는 Wiki는 404로 거절한다")
    void rejectsUnknownWiki() {
        adminLoggedIn();
        given(wikiRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendChatMessage(999L, "정리해줘."))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_NOT_FOUND);
        assertThatThrownBy(() -> service.getChatMessages(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_NOT_FOUND);
    }

    @Test
    @DisplayName("관리자가 아니면 전송도 조회도 할 수 없다")
    void rejectsNonAdmin() {
        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(20L, CurrentMemberRole.EMPLOYEE));

        assertThatThrownBy(() -> service.sendChatMessage(101L, "정리해줘."))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
        assertThatThrownBy(() -> service.getChatMessages(101L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_PERMISSION_REQUIRED);
    }

    @Test
    @DisplayName("FastAPI 수정 호출이 실패하면 대화를 저장하지 않는다")
    void doesNotSaveMessagesWhenEditFails() throws Exception {
        Wiki wiki = wiki(101L, 9L, "휴가 규정", List.of(), List.of());
        adminLoggedIn();
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        given(wikiFileStorage.readWikiMarkdown("wiki/ALL/pages/101.md")).willReturn("# 휴가 규정");
        given(wikiRepository.findAllByScopeKey(SCOPE_KEY)).willReturn(List.of(wiki));
        given(wikiChatMessageRepository.findAllByWikiIdInOrderByCreatedAtAscIdAsc(List.of(101L))).willReturn(List.of());
        given(aiJobRepository.existsByScopeKeyAndStatusIn(anyString(), anyCollection())).willReturn(false);
        given(aiClient.editWiki(any(WikiEditRequest.class))).willThrow(new AiClientException(
                AiClientFailureType.SERVER_ERROR,
                500,
                "WIKI_EDIT_FAILED",
                "Wiki 수정에 실패했습니다.",
                List.<FieldErrorResponse>of(),
                null
        ));

        assertThatThrownBy(() -> service.sendChatMessage(101L, "정리해줘."))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_EDIT_FAILED);
        verify(wikiChatMessageRepository, never()).save(any(WikiChatMessage.class));
        verify(applier, never()).apply(anyString(), any(WikiEditResponse.class));
    }

    @Test
    @DisplayName("AI 서버 연결 실패는 503(AI_SERVER_UNAVAILABLE)으로 내린다")
    void mapsAiConnectionFailureToServiceUnavailable() throws Exception {
        Wiki wiki = wiki(101L, 9L, "휴가 규정", List.of(), List.of());
        adminLoggedIn();
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        given(wikiFileStorage.readWikiMarkdown("wiki/ALL/pages/101.md")).willReturn("# 휴가 규정");
        given(wikiRepository.findAllByScopeKey(SCOPE_KEY)).willReturn(List.of(wiki));
        given(wikiChatMessageRepository.findAllByWikiIdInOrderByCreatedAtAscIdAsc(List.of(101L))).willReturn(List.of());
        given(aiJobRepository.existsByScopeKeyAndStatusIn(anyString(), anyCollection())).willReturn(false);
        given(aiClient.editWiki(any(WikiEditRequest.class))).willThrow(new AiClientException(
                AiClientFailureType.CONNECTION_FAILED,
                null,
                null,
                "FastAPI에 연결하지 못했습니다.",
                List.<FieldErrorResponse>of(),
                null
        ));

        assertThatThrownBy(() -> service.sendChatMessage(101L, "정리해줘."))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_SERVER_UNAVAILABLE);
        verify(wikiChatMessageRepository, never()).save(any(WikiChatMessage.class));
        verify(applier, never()).apply(anyString(), any(WikiEditResponse.class));
    }

    @Test
    @DisplayName("대화를 오래된 순서로 조회한다")
    void getsChatMessages() throws Exception {
        Wiki wiki = wiki(101L, 9L, "휴가 규정", List.of(), List.of());
        adminLoggedIn();
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        WikiChatMessage admin = WikiChatMessage.fromAdmin(wiki, 10L, "정리해줘.");
        assign(admin, "id", 1L);
        assign(admin, "createdAt", java.time.Instant.parse("2026-07-29T09:00:00Z"));
        WikiChatMessage agent = existingAgentMessage(wiki, "반영했습니다.");
        given(wikiRepository.findAllByScopeKey(SCOPE_KEY)).willReturn(List.of(wiki));
        given(wikiChatMessageRepository.findAllByWikiIdInOrderByCreatedAtAscIdAsc(List.of(101L)))
                .willReturn(List.of(admin, agent));

        WikiChatMessageListResponse response = service.getChatMessages(101L);

        assertThat(response.items())
                .extracting(item -> item.messageId() + ":" + item.senderType())
                .containsExactly("1:admin", "2:agent");
    }

    @Test
    @DisplayName("같은 scope의 다른 위키에서 나눈 대화도 함께 조회한다(S15P11B106-220)")
    void getsChatMessagesAcrossWikisInSameScope() throws Exception {
        Wiki wiki = wiki(101L, 9L, "휴가 규정", List.of(), List.of());
        Wiki otherWikiInScope = wiki(108L, 9L, "근태 관리", List.of(), List.of());
        adminLoggedIn();
        given(wikiRepository.findById(101L)).willReturn(Optional.of(wiki));
        WikiChatMessage aboutOtherWiki = WikiChatMessage.fromAdmin(otherWikiInScope, 10L, "근태 페이지도 정리해줘.");
        assign(aboutOtherWiki, "id", 1L);
        assign(aboutOtherWiki, "createdAt", java.time.Instant.parse("2026-07-29T09:00:00Z"));
        WikiChatMessage aboutThisWiki = existingAgentMessage(wiki, "휴가 페이지를 반영했습니다.");
        given(wikiRepository.findAllByScopeKey(SCOPE_KEY)).willReturn(List.of(wiki, otherWikiInScope));
        given(wikiChatMessageRepository.findAllByWikiIdInOrderByCreatedAtAscIdAsc(List.of(101L, 108L)))
                .willReturn(List.of(aboutOtherWiki, aboutThisWiki));

        WikiChatMessageListResponse response = service.getChatMessages(101L);

        assertThat(response.items())
                .extracting(item -> item.messageId() + ":" + item.senderType())
                .containsExactly("1:admin", "2:agent");
    }

    private void adminLoggedIn() {
        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(10L, CurrentMemberRole.ADMIN));
    }

    private WikiChatMessage existingAgentMessage(Wiki wiki, String content) throws Exception {
        WikiChatMessage message = WikiChatMessage.fromAgent(wiki, content);
        assign(message, "id", 2L);
        assign(message, "createdAt", java.time.Instant.parse("2026-07-29T09:00:30Z"));
        return message;
    }

    private Document document(long id, String fileName) throws Exception {
        Document document = Document.uploaded(
                10L,
                7L,
                SCOPE_KEY,
                fileName,
                "wiki/ALL/sources/%d/original.pdf".formatted(id),
                "application/pdf",
                1024L
        );
        assign(document, "id", id);
        document.startParsing();
        document.completeParsing("wiki/ALL/sources/%d/parsed.md".formatted(id));
        return document;
    }

    private Wiki wiki(long id, long categoryId, String title, List<Long> documentRefs, List<Long> wikiRefs)
            throws Exception {
        Wiki wiki = Wiki.create(SCOPE_KEY, categoryId, title);
        assign(wiki, "id", id);
        wiki.assignStoragePath();
        wiki.addDocumentRefs(documentRefs);
        wikiRefs.forEach(wiki::addWikiRef);
        return wiki;
    }

    private WikiCategory category(long id, String name) throws Exception {
        WikiCategory category = WikiCategory.create(SCOPE_KEY, name, null);
        assign(category, "id", id);
        return category;
    }

    private void assign(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
