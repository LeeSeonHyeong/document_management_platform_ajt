package com.ajt.backend.domain.document.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

    public LocalDateTime startedAt() {
        return startedAt;
    }

    public record DocumentParseResult(long documentId, boolean success, String failureReason) {
    }
}
