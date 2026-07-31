package com.ajt.backend.domain.question;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * 질문 1건에 대한 AI 답변 본문입니다. 질문과 1:1이며(uk_ai_answer_question), 실패한 질문에는 답변이 없습니다.
 */
@Getter
@Entity
@Table(name = "ai_answer")
public class AiAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ai_answer_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_question_id", nullable = false, unique = true)
    private AiQuestion question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiAnswer() {
    }

    private AiAnswer(AiQuestion question, String content) {
        this.question = question;
        this.content = content;
    }

    public static AiAnswer create(AiQuestion question, String content) {
        return new AiAnswer(question, content);
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
