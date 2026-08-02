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

    public void cancel() {
        if (status != DocumentStatus.UPLOADED) {
            throw new IllegalStateException("UPLOADED 상태의 문서만 취소할 수 있습니다.");
        }
        status = DocumentStatus.CANCELLED;
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

    /**
     * 파일 교체 확정(promote)이 커밋 후 실패한 경우처럼, 재처리를 시작하기도 전에 문서를 실패로 표시합니다(S15P11B106-146).
     * DB에는 이미 새 파일 메타데이터가 커밋됐지만 실제 파일이 최종 경로에 없을 수 있어, 문서 자체를 FAILED로 남겨
     * 다운로드/상세 조회에서 정상인 것처럼 보이지 않게 한다. 처리 중(PARSING/PROCESSING) 문서에는 쓰지 않는다.
     */
    public void failReplace(String failureReason) {
        if (isInProgress()) {
            throw new IllegalStateException("처리 중인 문서는 교체 실패로 표시할 수 없습니다.");
        }
        this.failureReason = failureReason;
        this.status = DocumentStatus.FAILED;
    }

    public void changeOriginalPath(String originalPath) {
        this.originalPath = originalPath;
    }

    /**
     * DOC: 원본 파일 교체. 파일명·저장경로·MIME·크기를 새 파일 기준으로 갱신한다.
     * 문서 ID·카테고리·공개범위(scopeKey)는 유지한다. 재처리 전환은 별도 호출({@link #markForReprocess()})로 한다.
     */
    public void replaceFile(String originalFileName, String originalPath, String mimeType, long fileSize) {
        this.originalFileName = originalFileName;
        this.originalPath = originalPath;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
    }

    /** 처리 중(파싱·변환 진행)인지 여부. 수정·교체·삭제 요청은 처리 중이면 거부한다(409). */
    public boolean isInProgress() {
        return status == DocumentStatus.PARSING || status == DocumentStatus.PROCESSING;
    }

    /**
     * 재처리를 위해 문서를 UPLOADED로 되돌린다(실패/취소/완료 문서를 다시 파싱 대상으로).
     * 처리 중인 문서는 되돌릴 수 없다.
     */
    public void markForReprocess() {
        if (isInProgress()) {
            throw new IllegalStateException("처리 중인 문서는 재처리 대상으로 되돌릴 수 없습니다.");
        }
        this.status = DocumentStatus.UPLOADED;
        this.failureReason = null;
    }

    /** DOC-05 메타데이터 수정: 카테고리와 공개 범위(scopeKey)를 함께 변경한다. */
    public void changeCategoryAndScope(long documentCategoryId, String scopeKey) {
        this.documentCategoryId = documentCategoryId;
        this.scopeKey = scopeKey;
    }

    public void changeCategoryScopeAndPaths(long documentCategoryId, String scopeKey, String originalPath, String parsedPath) {
        changeCategoryAndScope(documentCategoryId, scopeKey);
        this.originalPath = originalPath;
        this.parsedPath = parsedPath;
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
