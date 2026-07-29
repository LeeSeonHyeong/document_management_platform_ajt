package com.ajt.backend.domain.document.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_id")
    private Long id;

    @Column(name = "uploader_id", nullable = false)
    private long uploaderId;

    @Column(name = "document_category_id", nullable = false)
    private long documentCategoryId;

    @Column(name = "scope_key", nullable = false, length = 255)
    private String scopeKey;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "original_path", nullable = false, length = 500)
    private String originalPath;

    @Column(name = "parsed_path", length = 500)
    private String parsedPath;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document_wiki_refs", nullable = false, columnDefinition = "json")
    private List<Long> documentWikiRefs = List.of();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DocumentStatus status;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Document() {
    }

    private Document(
            long uploaderId,
            long documentCategoryId,
            String scopeKey,
            String originalFileName,
            String originalPath,
            String mimeType,
            long fileSize
    ) {
        this.uploaderId = uploaderId;
        this.documentCategoryId = documentCategoryId;
        this.scopeKey = scopeKey;
        this.originalFileName = originalFileName;
        this.originalPath = originalPath;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
        this.status = DocumentStatus.UPLOADED;
    }

    public static Document uploaded(
            long uploaderId,
            long documentCategoryId,
            String scopeKey,
            String originalFileName,
            String originalPath,
            String mimeType,
            long fileSize
    ) {
        return new Document(
                uploaderId,
                documentCategoryId,
                scopeKey,
                originalFileName,
                originalPath,
                mimeType,
                fileSize
        );
    }

    public void startParsing() {
        if (status != DocumentStatus.UPLOADED) {
            throw new IllegalStateException("UPLOADED 상태의 문서만 파싱할 수 있습니다.");
        }
        status = DocumentStatus.PARSING;
    }

    public void completeParsing(String parsedPath) {
        completeParsing(parsedPath, List.of());
    }

    public void completeParsing(String parsedPath, List<Long> documentWikiRefs) {
        ensureParsing();
        this.parsedPath = parsedPath;
        this.documentWikiRefs = List.copyOf(documentWikiRefs);
        this.failureReason = null;
        this.status = DocumentStatus.PROCESSING;
    }

    /**
     * Wiki 변환 결과까지 반영된 문서를 완료 처리합니다.
     * 변환으로 생성·수정된 Wiki가 문서의 참조 목록이 됩니다.
     */
    public void completeProcessing(List<Long> documentWikiRefs) {
        if (status != DocumentStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태의 문서만 완료할 수 있습니다.");
        }
        this.documentWikiRefs = List.copyOf(documentWikiRefs);
        this.failureReason = null;
        this.status = DocumentStatus.COMPLETED;
    }

    public void failParsing(String failureReason) {
        ensureParsing();
        this.failureReason = failureReason;
        this.status = DocumentStatus.FAILED;
    }

    /**
     * Wiki 변환 단계에서 실패한 문서를 실패 처리합니다.
     */
    public void failProcessing(String failureReason) {
        if (status != DocumentStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태의 문서만 변환 실패로 처리할 수 있습니다.");
        }
        this.failureReason = failureReason;
        this.status = DocumentStatus.FAILED;
    }

    public void retryParsing() {
        if (status != DocumentStatus.FAILED && status != DocumentStatus.CANCELLED) {
            throw new IllegalStateException("FAILED 또는 CANCELLED 상태의 문서만 재시도할 수 있습니다.");
        }
        this.status = DocumentStatus.UPLOADED;
        this.failureReason = null;
    }

    public void changeOriginalPath(String originalPath) {
        this.originalPath = originalPath;
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

    public Long id() {
        return id;
    }

    public long uploaderId() {
        return uploaderId;
    }

    public long documentCategoryId() {
        return documentCategoryId;
    }

    public String scopeKey() {
        return scopeKey;
    }

    public String originalFileName() {
        return originalFileName;
    }

    public String originalPath() {
        return originalPath;
    }

    public String parsedPath() {
        return parsedPath;
    }

    public List<Long> documentWikiRefs() {
        return List.copyOf(documentWikiRefs);
    }

    public String mimeType() {
        return mimeType;
    }

    public long fileSize() {
        return fileSize;
    }

    public DocumentStatus status() {
        return status;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private void ensureParsing() {
        if (status != DocumentStatus.PARSING) {
            throw new IllegalStateException("PARSING 상태의 문서만 파싱 결과를 처리할 수 있습니다.");
        }
    }
}
