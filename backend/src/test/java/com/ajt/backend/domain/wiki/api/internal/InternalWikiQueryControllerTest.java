package com.ajt.backend.domain.wiki.api.internal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.wiki.service.InternalWikiQueryService;
import com.ajt.backend.global.ai.capability.WikiCapability;
import com.ajt.backend.global.ai.capability.WikiCapabilityService;
import com.ajt.backend.global.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("내부 Wiki 조회 창구 라우팅")
class InternalWikiQueryControllerTest {

    private final InternalWikiQueryService queryService = mock(InternalWikiQueryService.class);
    private final WikiCapabilityService capabilityService = mock(WikiCapabilityService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalWikiQueryController(queryService, capabilityService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        given(capabilityService.require(anyString(), anyString()))
                .willReturn(new WikiCapability("ALL", 47L, Instant.now().plusSeconds(60)));
    }

    @Test
    @DisplayName("검색은 capability 검증 뒤 기본 limit 10으로 서비스에 위임한다")
    void searchesWithDefaultLimit() throws Exception {
        given(queryService.search("ALL", "휴가", 10))
                .willReturn(new InternalWikiQueryService.WikiSearch(47L, List.of()));

        mockMvc.perform(get("/internal/v1/wiki-search")
                        .header("X-Wiki-Capability", "capability")
                        .param("scopeKey", "ALL")
                        .param("query", "휴가"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeVersion").value(47))
                .andExpect(jsonPath("$.items").isArray());

        then(capabilityService).should().require("capability", "ALL");
        then(queryService).should().search("ALL", "휴가", 10);
    }

    @Test
    @DisplayName("검색 limit가 50을 넘으면 서비스 호출 전 400으로 거절한다")
    void rejectsSearchLimitOverMaximum() throws Exception {
        mockMvc.perform(get("/internal/v1/wiki-search")
                        .header("X-Wiki-Capability", "capability")
                        .param("scopeKey", "ALL")
                        .param("query", "휴가")
                        .param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        then(queryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("목록은 허용 최대 limit 500과 cursor를 서비스에 그대로 전달한다")
    void listsPagesWithCursor() throws Exception {
        given(queryService.pages("ALL", 500, "101"))
                .willReturn(new InternalWikiQueryService.WikiPages(47L, null, List.of()));

        mockMvc.perform(get("/internal/v1/wiki-pages")
                        .header("X-Wiki-Capability", "capability")
                        .param("scopeKey", "ALL")
                        .param("limit", "500")
                        .param("cursor", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        then(queryService).should().pages("ALL", 500, "101");
    }
}
