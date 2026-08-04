package com.ajt.backend.domain.wiki.api;

import static org.hamcrest.Matchers.empty;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ajt.backend.domain.wiki.service.WikiChatMessageService;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import com.ajt.backend.global.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Wiki 관리자 대화 API")
class WikiChatMessageControllerTest {

    private final WikiChatMessageService wikiChatMessageService =
            org.mockito.Mockito.mock(WikiChatMessageService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WikiChatMessageController(wikiChatMessageService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("대화 조회는 items에 발신 주체와 내용을 담아 반환한다")
    void getsChatMessages() throws Exception {
        given(wikiChatMessageService.getChatMessages(101L)).willReturn(new WikiChatMessageListResponse(List.of(
                new WikiChatMessageResponse(
                        "1", "admin", "중복 규정을 정리해줘.", Instant.parse("2026-07-29T09:00:00Z"), "101", "휴가 규정"),
                new WikiChatMessageResponse(
                        "2", "agent", "반영했습니다.", Instant.parse("2026-07-29T09:00:30Z"), "101", "휴가 규정")
        )));

        mockMvc.perform(get("/api/v1/wikis/101/chat-messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].messageId").value("1"))
                .andExpect(jsonPath("$.items[0].senderType").value("admin"))
                .andExpect(jsonPath("$.items[0].content").value("중복 규정을 정리해줘."))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-07-29T09:00:00Z"))
                .andExpect(jsonPath("$.items[0].wikiId").value("101"))
                .andExpect(jsonPath("$.items[0].wikiTitle").value("휴가 규정"))
                .andExpect(jsonPath("$.items[1].senderType").value("agent"));
    }

    @Test
    @DisplayName("대화가 없으면 빈 목록을 반환한다")
    void getsEmptyChatMessages() throws Exception {
        given(wikiChatMessageService.getChatMessages(101L))
                .willReturn(new WikiChatMessageListResponse(List.of()));

        mockMvc.perform(get("/api/v1/wikis/101/chat-messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").value(empty()));
    }

    @Test
    @DisplayName("수정 지시 전송은 관리자 메시지, 에이전트 응답과 수정된 Wiki를 반환한다")
    void sendsChatMessage() throws Exception {
        given(wikiChatMessageService.sendChatMessage(101L, "중복 규정을 정리해줘.")).willReturn(
                new WikiChatReplyResponse(
                        new WikiChatMessageResponse(
                                "3",
                                "admin",
                                "중복 규정을 정리해줘.",
                                Instant.parse("2026-07-29T09:10:00Z"),
                                "101",
                                "휴가 규정"
                        ),
                        new WikiChatMessageResponse(
                                "4",
                                "agent",
                                "중복된 연차 항목을 정리했습니다.",
                                Instant.parse("2026-07-29T09:10:20Z"),
                                "101",
                                "휴가 규정"
                        ),
                        new WikiDetailResponse(
                                "101",
                                "휴가 규정",
                                "# 휴가 규정\n정리된 본문",
                                new WikiDetailResponse.Category("9", "휴가 및 근태"),
                                "D1-D2",
                                List.of(WikiDetailResponse.EvidenceDocument.of(15L, "취업규칙.pdf")),
                                List.of(new WikiDetailResponse.RelatedWiki("108", "근태 관리")),
                                Instant.parse("2026-07-29T09:10:20Z")
                        )
                )
        );

        mockMvc.perform(post("/api/v1/wikis/101/chat-messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"중복 규정을 정리해줘.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminMessage.messageId").value("3"))
                .andExpect(jsonPath("$.adminMessage.senderType").value("admin"))
                .andExpect(jsonPath("$.agentMessage.senderType").value("agent"))
                .andExpect(jsonPath("$.agentMessage.content").value("중복된 연차 항목을 정리했습니다."))
                .andExpect(jsonPath("$.updatedWiki.wikiId").value("101"))
                .andExpect(jsonPath("$.updatedWiki.category.wikiCategoryId").value("9"))
                .andExpect(jsonPath("$.updatedWiki.scopeKey").value("D1-D2"))
                .andExpect(jsonPath("$.updatedWiki.evidenceDocuments[0].downloadUrl")
                        .value("/api/v1/documents/15/file"))
                .andExpect(jsonPath("$.updatedWiki.relatedWikis[0].wikiId").value("108"));
    }

    @Test
    @DisplayName("내용이 비어 있으면 400 EMPTY_CHAT_CONTENT를 반환한다")
    void rejectsBlankContent() throws Exception {
        willThrow(new BusinessException(ErrorCode.EMPTY_CHAT_CONTENT))
                .given(wikiChatMessageService).sendChatMessage(101L, "");

        mockMvc.perform(post("/api/v1/wikis/101/chat-messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_CHAT_CONTENT"));
    }

    @Test
    @DisplayName("변환 작업이 처리 중이면 409 WIKI_EDIT_IN_PROGRESS를 반환한다")
    void rejectsWhenJobInProgress() throws Exception {
        willThrow(new BusinessException(ErrorCode.WIKI_EDIT_IN_PROGRESS))
                .given(wikiChatMessageService).sendChatMessage(101L, "정리해줘.");

        mockMvc.perform(post("/api/v1/wikis/101/chat-messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"정리해줘.\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WIKI_EDIT_IN_PROGRESS"));
    }

    @Test
    @DisplayName("존재하지 않는 Wiki는 404 WIKI_NOT_FOUND를 반환한다")
    void rejectsUnknownWiki() throws Exception {
        given(wikiChatMessageService.getChatMessages(999L))
                .willThrow(new BusinessException(ErrorCode.WIKI_NOT_FOUND));

        mockMvc.perform(get("/api/v1/wikis/999/chat-messages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WIKI_NOT_FOUND"));
    }

    @Test
    @DisplayName("관리자가 아니면 403을 반환한다")
    void rejectsNonAdmin() throws Exception {
        given(wikiChatMessageService.getChatMessages(101L))
                .willThrow(new BusinessException(ErrorCode.ADMIN_PERMISSION_REQUIRED));

        mockMvc.perform(get("/api/v1/wikis/101/chat-messages"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_REQUIRED"));
    }
}
