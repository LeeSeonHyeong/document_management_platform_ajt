package com.ajt.backend.domain.question;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 답변 출처입니다. Wiki 또는 일정 중 하나를 가리키며(둘 중 하나만 not-null), 표시용 제목을 함께 보관합니다(FR-QNA-006).
 * wiki/schedule 원본 행이 삭제되면 FK는 SET NULL 되지만 source_title은 남아 이력 표시가 유지됩니다.
 */
@Getter
@Entity
@Table(name = "answer_source")
public class AnswerSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "answer_source_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_answer_id", nullable = false)
    private AiAnswer answer;

    @Column(name = "wiki_id")
    private Long wikiId;

    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "source_title", nullable = false, length = 255)
    private String sourceTitle;

    protected AnswerSource() {
    }

    private AnswerSource(AiAnswer answer, Long wikiId, Long scheduleId, String sourceTitle) {
        this.answer = answer;
        this.wikiId = wikiId;
        this.scheduleId = scheduleId;
        this.sourceTitle = sourceTitle;
    }

    public static AnswerSource wiki(AiAnswer answer, Long wikiId, String sourceTitle) {
        return new AnswerSource(answer, wikiId, null, sourceTitle);
    }

    public static AnswerSource schedule(AiAnswer answer, Long scheduleId, String sourceTitle) {
        return new AnswerSource(answer, null, scheduleId, sourceTitle);
    }
}
