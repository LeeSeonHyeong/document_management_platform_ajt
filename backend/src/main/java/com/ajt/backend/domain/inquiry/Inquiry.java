package com.ajt.backend.domain.inquiry;

import com.ajt.backend.domain.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 문의 엔티티입니다.
 * 등록자(author)와 등록자가 직접 선택한 담당자(assignee)를 함께 보관하며,
 * 첨부 이미지 메타데이터는 별도 테이블 없이 JSON 컬럼에 저장합니다.
 */
@Getter
@Entity
@Table(name = "inquiry")
public class Inquiry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inquiry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id", nullable = false)
    private Member assignee;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InquiryPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InquiryStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachment_refs", nullable = false, columnDefinition = "json")
    private List<InquiryAttachment> attachmentRefs = List.of();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Inquiry() {
    }

    private Inquiry(Member author, Member assignee, String title, String content, InquiryPriority priority) {
        this.author = Objects.requireNonNull(author, "등록자는 필수입니다.");
        this.assignee = Objects.requireNonNull(assignee, "담당자는 필수입니다.");
        this.title = requireTitle(title);
        this.content = requireContent(content);
        this.priority = Objects.requireNonNull(priority, "우선순위는 필수입니다.");
        this.status = InquiryStatus.PENDING;
    }

    public static Inquiry create(Member author, Member assignee, String title, String content, InquiryPriority priority) {
        return new Inquiry(author, assignee, title, content, priority);
    }

    /**
     * 저장 후 발급된 문의 ID로 첨부 경로를 만든 뒤, 첨부 메타데이터를 반영합니다.
     */
    public void attach(List<InquiryAttachment> attachments) {
        this.attachmentRefs = List.copyOf(attachments);
        touch();
    }

    /**
     * 답변이 등록되면 처리 완료 상태로 전환합니다.
     */
    public void markAnswered() {
        this.status = InquiryStatus.DONE;
        touch();
    }

    /**
     * 답변이 삭제되면 다시 답변 대기 상태로 되돌립니다.
     */
    public void markPending() {
        this.status = InquiryStatus.PENDING;
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

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("제목은 필수 입력 항목입니다.");
        }
        String trimmed = title.trim();
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException("제목은 200자 이하로 입력해야 합니다.");
        }
        return trimmed;
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("내용은 필수 입력 항목입니다.");
        }
        return content;
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
