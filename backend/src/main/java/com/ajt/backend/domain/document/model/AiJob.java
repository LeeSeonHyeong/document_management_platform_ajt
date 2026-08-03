package com.ajt.backend.domain.document.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_job")
public class AiJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_id")
    private Long id;

    @Column(name = "requester_id", nullable = false)
    private long requesterId;

    @Column(name = "scope_key", nullable = false, length = 255)
    private String scopeKey;

    @Column(name = "workspace_path", nullable = false, length = 500)
    private String workspacePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AiJobStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document_ids", nullable = false, columnDefinition = "json")
    private List<Long> documentIds = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document_results", columnDefinition = "json")
    private List<DocumentParseResult> documentResults;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    protected AiJob() {
    }

    private AiJob(long requesterId, String scopeKey, String workspacePath, List<Long> documentIds) {
        this.requesterId = requesterId;
        this.scopeKey = scopeKey;
        this.workspacePath = workspacePath;
        this.documentIds = List.copyOf(documentIds);
        this.status = AiJobStatus.WAITING;
    }

    public static AiJob waiting(long requesterId, String scopeKey, String workspacePath, List<Long> documentIds) {
        return new AiJob(requesterId, scopeKey, workspacePath, documentIds);
    }

    public void start() {
        if (status != AiJobStatus.WAITING) {
            throw new IllegalStateException("WAITING 상태의 작업만 시작할 수 있습니다.");
        }
        status = AiJobStatus.PROCESSING;
        startedAt = LocalDateTime.now();
    }

    /**
     * 문서별 처리 결과를 기록하고 작업을 종료합니다.
     * 문서가 하나라도 성공하면 COMPLETED, 전부 실패하면 FAILED가 됩니다.
     */
    public void finish(List<DocumentParseResult> documentResults) {
        requireProcessing();
        this.documentResults = List.copyOf(documentResults);
        boolean anySucceeded = documentResults.stream().anyMatch(DocumentParseResult::success);
        if (anySucceeded) {
            this.status = AiJobStatus.COMPLETED;
            this.failureReason = null;
        } else {
            this.status = AiJobStatus.FAILED;
            this.failureReason = firstFailureReason(documentResults);
        }
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * 문서 단위가 아니라 작업 자체가 실패한 경우입니다. (예: 문서 목록을 읽지 못함)
     */
    public void fail(String failureReason) {
        requireProcessing();
        this.status = AiJobStatus.FAILED;
        this.failureReason = failureReason;
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * 아직 시작하지 못한 작업을 실패로 마감합니다(S15P11B106-146).
     * 예: 파일 교체 확정(staging→최종 이동)이 커밋 후 실패해 재처리를 시작조차 못 한 경우.
     */
    public void failBeforeStart(String failureReason) {
        if (status != AiJobStatus.WAITING) {
            throw new IllegalStateException("WAITING 상태의 작업만 시작 전 실패 처리할 수 있습니다.");
        }
        this.status = AiJobStatus.FAILED;
        this.failureReason = failureReason;
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * 아직 시작하지 않은 문서를 멈추라는 관리자 요청입니다. 이미 시작한 문서의 결과는
     * {@link #recordResult(DocumentParseResult)}로 계속 누적할 수 있습니다.
     */
    public void cancel() {
        if (status != AiJobStatus.WAITING && status != AiJobStatus.PROCESSING) {
            throw new IllegalStateException("대기 또는 처리 중인 작업만 취소할 수 있습니다.");
        }
        status = AiJobStatus.CANCELLED;
        finishedAt = LocalDateTime.now();
    }

    /** 현재 처리 중이던 문서의 결과를 기존 JSON 결과에 누적한다. */
    public void recordResult(DocumentParseResult result) {
        if (status != AiJobStatus.PROCESSING && status != AiJobStatus.CANCELLED) {
            throw new IllegalStateException("처리 중이거나 취소된 작업에만 문서 결과를 기록할 수 있습니다.");
        }
        List<DocumentParseResult> updated = new java.util.ArrayList<>(documentResults());
        updated.removeIf(existing -> existing.documentId() == result.documentId());
        updated.add(result);
        documentResults = List.copyOf(updated);
    }

    private void requireProcessing() {
        if (status != AiJobStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태의 작업만 종료할 수 있습니다.");
        }
    }

    private static String firstFailureReason(List<DocumentParseResult> documentResults) {
        return documentResults.stream()
                .filter(result -> !result.success())
                .map(DocumentParseResult::failureReason)
                .filter(reason -> reason != null && !reason.isBlank())
                .findFirst()
                .orElse("문서를 Wiki로 변환하지 못했습니다.");
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public Long id() {
        return id;
    }

    public long requesterId() {
        return requesterId;
    }

    public String scopeKey() {
        return scopeKey;
    }

    public String workspacePath() {
        return workspacePath;
    }

    public AiJobStatus status() {
        return status;
    }

    public List<Long> documentIds() {
        return List.copyOf(documentIds);
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalDateTime startedAt() {
        return startedAt;
    }

    public LocalDateTime finishedAt() {
        return finishedAt;
    }

    public String failureReason() {
        return failureReason;
    }

    public List<DocumentParseResult> documentResults() {
        return documentResults == null ? List.of() : List.copyOf(documentResults);
    }

    /**
     * 문서 한 건의 처리 결과입니다. ai_job.document_results JSON으로 저장됩니다.
     *
     * <p>{@code summary}는 FastAPI Wiki 변환 응답의 작업 요약이고,
     * {@code failureStage}는 오류 응답의 실패 단계입니다. 둘 다 없으면 {@code null}입니다.
     *
     * <p>{@code originalFileName}은 <b>그때 그 파일 이름의 스냅샷</b>입니다(S15P11B106-202).
     * 문서는 하드 삭제되지만(DR-014) 이 결과는 최소 1년 보존되므로(NFR-LOG-001), 이름을 함께
     * 남기지 않으면 이력에 문서 ID만 남아 무엇이 바뀌었는지 알 수 없다. 답변 출처의 제목
     * 스냅샷(DR-021)·Wiki 채팅의 {@code wiki_title_snapshot}(DR-024)과 같은 방식이다.
     * 이 필드가 생기기 전에 저장된 결과는 {@code null}이다.
     */
    public record DocumentParseResult(
            long documentId,
            String originalFileName,
            boolean success,
            String summary,
            String failureReason,
            String failureStage
    ) {
        public static DocumentParseResult succeeded(long documentId, String originalFileName, String summary) {
            return new DocumentParseResult(documentId, originalFileName, true, summary, null, null);
        }

        public static DocumentParseResult failed(
                long documentId, String originalFileName, String failureReason, String failureStage) {
            return new DocumentParseResult(documentId, originalFileName, false, null, failureReason, failureStage);
        }
    }
}
