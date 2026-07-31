package com.ajt.backend.domain.question;

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
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * 사용자 질문 이력 엔티티입니다(FR-QNA-008).
 * 대화 키로 멀티턴을 묶고, 질문 유형·성공 여부·실패 사유를 함께 보관합니다.
 * 답변 생성(AI 연동)은 별도 기능이며, 이 도메인은 저장된 이력의 조회를 담당합니다.
 */
@Getter
@Entity
@Table(name = "ai_question")
public class AiQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ai_question_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "conversation_key", nullable = false, length = 100)
    private String conversationKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", length = 30)
    private QuestionType questionType;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiQuestion() {
    }

    private AiQuestion(
            Member member,
            String conversationKey,
            String content,
            QuestionType questionType,
            boolean success,
            String failureReason
    ) {
        this.member = member;
        this.conversationKey = conversationKey;
        this.content = content;
        this.questionType = questionType;
        this.success = success;
        this.failureReason = failureReason;
    }

    /**
     * 질문 이력 저장용 팩토리입니다. 성공/실패 이력을 모두 표현합니다(FR-QNA-008).
     */
    public static AiQuestion create(
            Member member,
            String conversationKey,
            String content,
            QuestionType questionType,
            boolean success,
            String failureReason
    ) {
        return new AiQuestion(member, conversationKey, content, questionType, success, failureReason);
    }

    /** 답변 생성이 성공했을 때 AI가 판단한 질문 유형과 함께 성공으로 표시합니다. */
    public void recordSuccess(QuestionType questionType) {
        this.questionType = questionType;
        this.success = true;
        this.failureReason = null;
    }

    /** 답변 생성이 실패했을 때 사유를 남깁니다. 실패 이력도 조회에 노출됩니다(FR-QNA-008). */
    public void recordFailure(String failureReason) {
        this.success = false;
        this.failureReason = failureReason;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
