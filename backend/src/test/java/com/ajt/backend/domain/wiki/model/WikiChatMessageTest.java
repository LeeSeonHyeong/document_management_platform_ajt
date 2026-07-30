package com.ajt.backend.domain.wiki.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Wiki 관리자 대화 엔티티")
class WikiChatMessageTest {

    @Test
    @DisplayName("관리자 메시지는 회원 ID와 Wiki 제목 스냅샷을 남긴다")
    void createsAdminMessage() throws Exception {
        Wiki wiki = wiki(101L, "휴가 규정");

        WikiChatMessage message = WikiChatMessage.fromAdmin(wiki, 10L, "  중복 규정을 정리해줘.  ");

        assertThat(message.wikiId()).isEqualTo(101L);
        assertThat(message.wikiTitleSnapshot()).isEqualTo("휴가 규정");
        assertThat(message.memberId()).isEqualTo(10L);
        assertThat(message.senderType()).isEqualTo(WikiChatSenderType.ADMIN);
        assertThat(message.content()).isEqualTo("중복 규정을 정리해줘.");
    }

    @Test
    @DisplayName("에이전트 메시지는 발신 회원이 없다")
    void createsAgentMessage() throws Exception {
        Wiki wiki = wiki(101L, "휴가 규정");

        WikiChatMessage message = WikiChatMessage.fromAgent(wiki, "반영했습니다.");

        assertThat(message.memberId()).isNull();
        assertThat(message.senderType()).isEqualTo(WikiChatSenderType.AGENT);
    }

    @Test
    @DisplayName("발신 주체는 API에서 소문자로 노출된다")
    void exposesLowerCaseSenderType() {
        assertThat(WikiChatSenderType.ADMIN.apiValue()).isEqualTo("admin");
        assertThat(WikiChatSenderType.AGENT.apiValue()).isEqualTo("agent");
    }

    @Test
    @DisplayName("내용이 비어 있으면 만들 수 없다")
    void rejectsBlankContent() throws Exception {
        Wiki wiki = wiki(101L, "휴가 규정");

        assertThatThrownBy(() -> WikiChatMessage.fromAgent(wiki, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대화 내용");
    }

    @Test
    @DisplayName("제목 스냅샷은 200자로 자른다")
    void truncatesLongTitleSnapshot() throws Exception {
        Wiki wiki = wiki(101L, "가".repeat(200));

        WikiChatMessage message = WikiChatMessage.fromAgent(wiki, "반영했습니다.");

        assertThat(message.wikiTitleSnapshot()).hasSize(200);
    }

    private Wiki wiki(long id, String title) throws ReflectiveOperationException {
        Wiki wiki = Wiki.create("ALL", 10L, title);
        Field idField = Wiki.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(wiki, id);
        return wiki;
    }
}
