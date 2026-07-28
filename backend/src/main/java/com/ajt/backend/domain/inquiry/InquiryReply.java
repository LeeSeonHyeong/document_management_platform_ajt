package com.ajt.backend.domain.inquiry;

import com.ajt.backend.domain.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;

/**
 * 문의 답변 엔티티입니다.
 * 문의 한 건당 답변은 최대 한 건만 존재하며(inquiry_id UNIQUE), 지정 담당자만 작성합니다.
 */
@Getter
@Entity
@Table(name = "inquiry_reply")
public class InquiryReply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inquiry_reply_id")
    private Long id;

    @Column(name = "inquiry_id", nullable = false, unique = true)
    private Long inquiryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member responder;

    @Column(name = "reply", nullable = false, columnDefinition = "TEXT")
    private String reply;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InquiryReply() {
    }

    private InquiryReply(Long inquiryId, Member responder, String reply) {
        this.inquiryId = Objects.requireNonNull(inquiryId, "문의 ID는 필수입니다.");
        this.responder = Objects.requireNonNull(responder, "답변 작성자는 필수입니다.");
        this.reply = requireReply(reply);
    }

    public static InquiryReply create(Long inquiryId, Member responder, String reply) {
        return new InquiryReply(inquiryId, responder, reply);
    }

    /**
     * 답변은 임시 저장 없이 전체 교체합니다.
     */
    public void updateReply(String reply) {
        this.reply = requireReply(reply);
        touch();
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    private String requireReply(String reply) {
        if (reply == null || reply.isBlank()) {
            throw new IllegalArgumentException("답변 내용을 입력해주세요.");
        }
        return reply;
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
