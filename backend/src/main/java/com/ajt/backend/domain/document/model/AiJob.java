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
     */
    public record DocumentParseResult(
            long documentId,
            boolean success,
            String summary,
            String failureReason,
            String failureStage
    ) {
        public static DocumentParseResult succeeded(long documentId, String summary) {
            return new DocumentParseResult(documentId, true, summary, null, null);
        }

        public static DocumentParseResult failed(long documentId, String failureReason, String failureStage) {
            return new DocumentParseResult(documentId, false, null, failureReason, failureStage);
        }
    }
}
