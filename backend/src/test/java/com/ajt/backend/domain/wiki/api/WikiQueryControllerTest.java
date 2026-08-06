package com.ajt.backend.domain.wiki.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.wiki.service.WikiQueryService;
import com.ajt.backend.global.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Wiki 목록·검색 API")
class WikiQueryControllerTest {

    private final WikiQueryService wikiQueryService = org.mockito.Mockito.mock(WikiQueryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WikiQueryController(wikiQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Wiki 목록 조회 성공 시 200과 목록·페이지 정보를 반환한다")
    void listsWikis() throws Exception {
        given(wikiQueryService.findWikis(any(), any(), any(), any(), any(), any()))
                .willReturn(new WikiListResponse(
                        List.of(new WikiSummaryResponse(
                                "101",
                                "a1b2c3d4e5f6",
                                "휴가 규정",
                                "연차와 반차 사용 기준",
                                "9",
                                "휴가 및 근태",
                                "D1-D2",
                                Instant.parse("2026-07-27T09:00:00Z")
                        )),
                        1,
                        20,
                        1,
                        1
                ));

        mockMvc.perform(get("/api/v1/wikis")
                        .param("scopeKey", "D1-D2")
                        .param("keyword", "연차"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].wikiId").value("101"))
                .andExpect(jsonPath("$.items[0].pageKey").value("a1b2c3d4e5f6"))
                .andExpect(jsonPath("$.items[0].title").value("휴가 규정"))
                .andExpect(jsonPath("$.items[0].summary").value("연차와 반차 사용 기준"))
                .andExpect(jsonPath("$.items[0].wikiCategoryName").value("휴가 및 근태"))
                .andExpect(jsonPath("$.items[0].scopeKey").value("D1-D2"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }
}
