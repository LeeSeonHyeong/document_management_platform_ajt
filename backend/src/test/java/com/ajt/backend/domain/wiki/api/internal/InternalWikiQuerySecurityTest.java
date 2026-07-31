package com.ajt.backend.domain.wiki.api.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.global.ai.capability.WikiCapabilityService;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.storage.DocumentFileStorage;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import static org.mockito.BDDMockito.given;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
@DisplayName("FastAPI 전용 Wiki 조회 창구 인증")
class InternalWikiQuerySecurityTest {

    private final MockMvc mockMvc;
    private final WikiCapabilityService capabilityService;
    private final WikiScopeRepository wikiScopeRepository;
    private final WikiCategoryRepository wikiCategoryRepository;
    private final WikiRepository wikiRepository;
    private final DocumentRepository documentRepository;

    @MockitoBean
    private WikiFileStorage wikiFileStorage;
    @MockitoBean
    private DocumentFileStorage documentFileStorage;

    @Autowired
    InternalWikiQuerySecurityTest(
            MockMvc mockMvc,
            WikiCapabilityService capabilityService,
            WikiScopeRepository wikiScopeRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiRepository wikiRepository,
            DocumentRepository documentRepository
    ) {
        this.mockMvc = mockMvc;
        this.capabilityService = capabilityService;
        this.wikiScopeRepository = wikiScopeRepository;
        this.wikiCategoryRepository = wikiCategoryRepository;
        this.wikiRepository = wikiRepository;
        this.documentRepository = documentRepository;
    }

    @Test
    @DisplayName("내부 API 키가 없으면 capability보다 먼저 401로 거절한다")
    void rejectsMissingInternalApiKey() throws Exception {
        mockMvc.perform(get("/internal/v1/wiki-search")
                        .param("scopeKey", "ALL")
                        .param("query", "휴가"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("유효한 내부 API 키라도 capability가 없으면 존재를 숨기는 404를 반환한다")
    void hidesMissingCapabilityAsNotFound() throws Exception {
        mockMvc.perform(get("/internal/v1/wiki-search")
                        .header("X-Internal-API-Key", "local-dev-key")
                        .param("scopeKey", "ALL")
                        .param("query", "휴가"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WIKI_CAPABILITY_EXPIRED"));
    }

    @Test
    @DisplayName("나머지 조회 endpoint는 capability가 없으면 데이터를 읽기 전에 404로 숨긴다")
    void hidesMissingCapabilityForEveryQueryEndpoint() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/internal/v1/wiki-pages").param("scopeKey", "ALL"),
                get("/internal/v1/wikis/101/content").param("scopeKey", "ALL"),
                get("/internal/v1/wikis/101/relations").param("scopeKey", "ALL"),
                get("/internal/v1/wiki-spaces/ALL/index"),
                get("/internal/v1/wiki-spaces/ALL/categories"),
                get("/internal/v1/wiki-spaces/ALL/relations"),
                get("/internal/v1/documents/15/parsed").param("scopeKey", "ALL")
        );

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.header("X-Internal-API-Key", "local-dev-key"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("WIKI_CAPABILITY_EXPIRED"));
        }
    }

    @Test
    @DisplayName("다른 scope에 발급된 capability는 404로 숨긴다")
    void hidesCapabilityUsedForAnotherScope() throws Exception {
        String capability = capabilityService.issue("D1-D2", 47L, Duration.ofMinutes(1));

        mockMvc.perform(get("/internal/v1/wiki-search")
                        .header("X-Internal-API-Key", "local-dev-key")
                        .header("X-Wiki-Capability", capability)
                        .param("scopeKey", "ALL")
                        .param("query", "휴가"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WIKI_CAPABILITY_EXPIRED"));
    }

    @Test
    @DisplayName("존재하지 않는 파싱 문서는 계약 오류 코드 DOCUMENT_NOT_FOUND를 반환한다")
    void returnsContractErrorForMissingParsedDocument() throws Exception {
        wikiScopeRepository.save(WikiScope.all());
        String capability = capabilityService.issue("ALL", 0L, Duration.ofMinutes(1));

        mockMvc.perform(get("/internal/v1/documents/15/parsed")
                        .header("X-Internal-API-Key", "local-dev-key")
                        .header("X-Wiki-Capability", capability)
                        .param("scopeKey", "ALL"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("유효 capability는 같은 공간의 본문·관계·목차·카테고리·목록·파싱본을 반환한다")
    void returnsQueryGatewayResourcesWithinCapabilityScope() throws Exception {
        wikiScopeRepository.save(WikiScope.all());
        WikiCategory category = wikiCategoryRepository.save(WikiCategory.create("ALL", "휴가", null));
        Document document = Document.uploaded(1L, 1L, "ALL", "취업규칙.pdf", "original.pdf", "application/pdf", 1L);
        document.startParsing();
        document.completeParsing("wiki/ALL/sources/15/parsed.md");
        document = documentRepository.save(document);
        Wiki wiki = wikiRepository.save(Wiki.create("ALL", category.id(), "휴가 규정"));
        wiki.assignStoragePath();
        wiki.changeSummary("연차 사용 기준");
        wiki.changeContentHash("a".repeat(64));
        wiki.addDocumentRefs(List.of(document.id()));
        wikiRepository.save(wiki);
        given(wikiFileStorage.readWikiMarkdown(wiki.wikiPath())).willReturn("# 휴가 규정");
        given(wikiFileStorage.readIndex("ALL")).willReturn("# 목차");
        given(documentFileStorage.readText(document.parsedPath())).willReturn("# 취업 규칙");
        String capability = capabilityService.issue("ALL", 0L, Duration.ofMinutes(1));

        mockMvc.perform(get("/internal/v1/wikis/{wikiId}/content", wiki.id())
                        .header("X-Internal-API-Key", "local-dev-key").header("X-Wiki-Capability", capability).param("scopeKey", "ALL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.wikiId").value(wiki.id().toString()))
                .andExpect(jsonPath("$.contentMarkdown").value("# 휴가 규정"));
        mockMvc.perform(get("/internal/v1/wikis/{wikiId}/relations", wiki.id())
                        .header("X-Internal-API-Key", "local-dev-key").header("X-Wiki-Capability", capability).param("scopeKey", "ALL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentRefs[0]").value(document.id().toString()));
        mockMvc.perform(get("/internal/v1/wiki-spaces/ALL/index")
                        .header("X-Internal-API-Key", "local-dev-key").header("X-Wiki-Capability", capability))
                .andExpect(status().isOk()).andExpect(jsonPath("$.indexMarkdown").value("# 목차"));
        mockMvc.perform(get("/internal/v1/wiki-spaces/ALL/categories")
                        .header("X-Internal-API-Key", "local-dev-key").header("X-Wiki-Capability", capability))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].wikiCount").value(1));
        mockMvc.perform(get("/internal/v1/wiki-pages").header("X-Internal-API-Key", "local-dev-key")
                        .header("X-Wiki-Capability", capability).param("scopeKey", "ALL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].wikiId").value(wiki.id().toString()));
        mockMvc.perform(get("/internal/v1/documents/{documentId}/parsed", document.id())
                        .header("X-Internal-API-Key", "local-dev-key").header("X-Wiki-Capability", capability).param("scopeKey", "ALL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parsedMarkdown").value("# 취업 규칙"));
    }

    @Test
    @DisplayName("범위 관계 조회도 내부 API 키가 없으면 401로 거절한다")
    void rejectsSpaceRelationsWithoutInternalApiKey() throws Exception {
        mockMvc.perform(get("/internal/v1/wiki-spaces/ALL/relations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
