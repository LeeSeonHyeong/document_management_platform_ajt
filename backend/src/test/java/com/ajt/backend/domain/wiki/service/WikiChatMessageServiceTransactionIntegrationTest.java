package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.service.CurrentMember;
import com.ajt.backend.domain.document.service.CurrentMemberProvider;
import com.ajt.backend.domain.document.service.CurrentMemberRole;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.member.DepartmentScopePolicy;
import com.ajt.backend.domain.member.ScopeAccess;
import com.ajt.backend.domain.wiki.api.WikiChatReplyResponse;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiChatMessageRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileMutation;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.WikiEditRequest;
import com.ajt.backend.global.ai.client.WikiEditResponse;
import com.ajt.backend.global.ai.client.WikiTransformationResponse.RelationChange;
import com.ajt.backend.global.ai.capability.WikiCapabilityService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 관리자 지시 문서의 REQUIRES_NEW 커밋과 바깥 채팅 트랜잭션의 1차 캐시를 함께 검증합니다.
 * Mockito 단위 테스트로는 바깥 Wiki가 stale 상태가 되는 실제 JPA 동작을 재현할 수 없습니다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_instruction_chat_tx;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "ajt.local-data.enabled=false"
})
@DisplayName("Wiki 관리자 대화 트랜잭션 통합테스트")
class WikiChatMessageServiceTransactionIntegrationTest {

    private static final long ADMIN_ID = 10L;
    private static final String SCOPE_KEY = "ALL";

    @Autowired
    private WikiChatMessageService service;
    @Autowired
    private WikiRepository wikiRepository;
    @Autowired
    private WikiCategoryRepository wikiCategoryRepository;
    @Autowired
    private WikiChatMessageRepository wikiChatMessageRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private DocumentCategoryRepository documentCategoryRepository;
    @Autowired
    private WikiScopeRepository wikiScopeRepository;

    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;
    @MockitoBean
    private DepartmentScopePolicy departmentScopePolicy;
    @MockitoBean
    private AiClient aiClient;
    @MockitoBean
    private WikiCapabilityService wikiCapabilityService;
    @MockitoBean
    private DocumentFileStorage documentFileStorage;
    @MockitoBean
    private WikiFileStorage wikiFileStorage;

    private Wiki sourceWiki;
    private Wiki targetWiki;

    @BeforeEach
    void setUp() throws Exception {
        wikiChatMessageRepository.deleteAll();
        documentRepository.deleteAll();
        wikiRepository.deleteAll();
        wikiCategoryRepository.deleteAll();
        documentCategoryRepository.deleteAll();
        wikiScopeRepository.deleteAll();

        wikiScopeRepository.saveAndFlush(WikiScope.all());
        documentCategoryRepository.saveAndFlush(DocumentCategory.create(SCOPE_KEY, "관리자 지시", null));
        WikiCategory wikiCategory = wikiCategoryRepository.saveAndFlush(
                WikiCategory.create(SCOPE_KEY, "휴가 및 근태", null));
        sourceWiki = persistWiki(wikiCategory.id(), "휴가 규정");
        targetWiki = persistWiki(wikiCategory.id(), "근태 관리");

        given(currentMemberProvider.currentMember())
                .willReturn(new CurrentMember(ADMIN_ID, CurrentMemberRole.ADMIN));
        given(departmentScopePolicy.resolve(ADMIN_ID)).willReturn(ScopeAccess.superAdmin());
        given(wikiCapabilityService.issue(eq(SCOPE_KEY), anyLong(), any(Duration.class)))
                .willReturn("capability");
        given(documentFileStorage.storeSynthesizedOriginal(eq(SCOPE_KEY), anyLong(), anyString()))
                .willAnswer(invocation -> sourcePath(invocation.getArgument(1), "original.md"));
        given(documentFileStorage.storeParsedMarkdown(eq(SCOPE_KEY), anyLong(), anyString()))
                .willAnswer(invocation -> sourcePath(invocation.getArgument(1), "parsed.md"));

        WikiFileMutation fileMutation = mock(WikiFileMutation.class);
        given(wikiFileStorage.beginMutation()).willReturn(fileMutation);
        given(wikiFileStorage.readIndex(SCOPE_KEY)).willReturn("");
        given(fileMutation.storeIndex(eq(SCOPE_KEY), anyString())).willReturn("wiki/ALL/index.md");
    }

    @Test
    @DisplayName("아무 변경도 없으면 선제 연결한 관리자 지시 문서를 근거에서 회수한다(S15P11B106-303)")
    void withdrawsCommittedDocumentReferenceForNoChangeReply() {
        // 옛 정책은 무변경에도 연결을 유지했다. 실사용(2026-08-06)에서 거부된 잡담 지시가
        // 위키의 근거 문서 목록·관계 그래프에 남는 것이 확인돼 정책을 뒤집었다.
        // 문서 행 자체는 스펙(2026-08-04)대로 보존한다 — 회수 대상은 연결(refs)뿐이다.
        given(aiClient.editWiki(any(WikiEditRequest.class))).willReturn(noChangeResponse());

        WikiChatReplyResponse response = service.sendChatMessage(sourceWiki.id(), "이미 반영됐는지 확인해줘.");

        long instructionDocumentId = onlyInstructionDocumentId();
        assertThat(response.updatedWiki().evidenceDocuments()).isEmpty();
        assertThat(wikiRepository.findById(sourceWiki.id()).orElseThrow().documentRefs()).isEmpty();
        assertThat(documentRepository.findById(instructionDocumentId).orElseThrow().documentWikiRefs())
                .isEmpty();
    }

    @Test
    @DisplayName("관계만 바꾸어 stale Wiki가 dirty 되어도 관리자 지시 문서 연결을 덮어쓰지 않는다")
    void keepsCommittedDocumentReferenceForRelationOnlyReply() {
        given(aiClient.editWiki(any(WikiEditRequest.class))).willReturn(new WikiEditResponse(
                "관계를 연결했습니다.",
                List.of(),
                List.of(),
                List.of(new RelationChange(
                        "add",
                        String.valueOf(sourceWiki.id()),
                        String.valueOf(targetWiki.id())
                )),
                List.of()
        ));

        service.sendChatMessage(sourceWiki.id(), "근태 관리 위키와 연결해줘.");

        long instructionDocumentId = onlyInstructionDocumentId();
        Wiki stored = wikiRepository.findById(sourceWiki.id()).orElseThrow();
        assertThat(stored.documentRefs()).containsExactly(instructionDocumentId);
        assertThat(stored.wikiRefs()).containsExactly(targetWiki.id());
    }

    private Wiki persistWiki(long categoryId, String title) {
        Wiki wiki = wikiRepository.saveAndFlush(Wiki.create(SCOPE_KEY, categoryId, title));
        wiki.assignStoragePath();
        return wikiRepository.saveAndFlush(wiki);
    }

    private long onlyInstructionDocumentId() {
        assertThat(documentRepository.findAll()).hasSize(1);
        return documentRepository.findAll().get(0).id();
    }

    private static WikiEditResponse noChangeResponse() {
        return new WikiEditResponse("변경할 내용이 없습니다.", List.of(), List.of(), List.of(), List.of());
    }

    private static String sourcePath(long documentId, String fileName) {
        return "wiki/ALL/sources/" + documentId + "/" + fileName;
    }
}
