package com.ajt.backend.domain.wiki.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 관리자와 Wiki 편집 에이전트가 나눈 대화 1건입니다.
 *
 * <p>FR-AI-004에 따라 작업(ai_job)이 아니라 Wiki에 연결됩니다.
 * Wiki가 삭제되면 {@code wiki_id}는 NULL이 되지만 대화는 남으므로 삭제 당시 제목을 함께 보관합니다.
 */
// TODO(DB): 계약의 GET /api/v1/wikis/{wikiId}/chat-messages 응답은 "작업 상태"를 포함하지만
//           wiki_chat_message에 저장할 컬럼이 없다. 프론트도 필드명 미확정으로 우선 생략한 상태다.
//           상태를 노출해야 하면 컬럼 추가가 필요하다. erd.sql 변경 필요 — 팀원 합의 후 진행. (임의 변경 금지)
@Entity
@Table(name = "wiki_chat_message")
public class WikiChatMessage {

    private static final int TITLE_SNAPSHOT_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long id;

    @Column(name = "wiki_id")
    private Long wikiId;

    @Column(name = "wiki_title_snapshot", nullable = false, length = TITLE_SNAPSHOT_MAX_LENGTH)
    private String wikiTitleSnapshot;

    @Column(name = "member_id")
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 30)
    private WikiChatSenderType senderType;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WikiChatMessage() {
    }

    private WikiChatMessage(
            Long wikiId,
            String wikiTitleSnapshot,
            Long memberId,
            WikiChatSenderType senderType,
            String content
    ) {
        this.wikiId = wikiId;
        this.wikiTitleSnapshot = requireTitleSnapshot(wikiTitleSnapshot);
        this.memberId = memberId;
        this.senderType = senderType;
        this.content = requireContent(content);
    }

    /**
     * 관리자가 보낸 수정 지시입니다.
     */
    public static WikiChatMessage fromAdmin(Wiki wiki, long memberId, String content) {
        return new WikiChatMessage(wiki.id(), wiki.title(), memberId, WikiChatSenderType.ADMIN, content);
    }

    /**
     * 에이전트가 보낸 응답입니다. 발신자가 회원이 아니므로 member_id는 비웁니다.
     */
    public static WikiChatMessage fromAgent(Wiki wiki, String content) {
        return new WikiChatMessage(wiki.id(), wiki.title(), null, WikiChatSenderType.AGENT, content);
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public Long id() {
        return id;
    }

    public Long wikiId() {
        return wikiId;
    }

    public String wikiTitleSnapshot() {
        return wikiTitleSnapshot;
    }

    public Long memberId() {
        return memberId;
    }

    public WikiChatSenderType senderType() {
        return senderType;
    }

    public String content() {
        return content;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static String requireTitleSnapshot(String wikiTitleSnapshot) {
        if (wikiTitleSnapshot == null || wikiTitleSnapshot.isBlank()) {
            throw new IllegalArgumentException("Wiki 제목 스냅샷은 비어 있을 수 없습니다.");
        }
        if (wikiTitleSnapshot.length() > TITLE_SNAPSHOT_MAX_LENGTH) {
            return wikiTitleSnapshot.substring(0, TITLE_SNAPSHOT_MAX_LENGTH);
        }
        return wikiTitleSnapshot;
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("대화 내용을 입력해주세요.");
        }
        return content.trim();
    }
}
