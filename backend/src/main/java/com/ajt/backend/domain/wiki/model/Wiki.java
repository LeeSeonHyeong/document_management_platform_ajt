package com.ajt.backend.domain.wiki.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * LLM Wiki 문서 엔티티입니다.
 * 본문 Markdown은 DB가 아니라 {@code wiki_path} 파일에 저장하고, 엔티티는 메타데이터와 참조만 관리합니다.
 *
 * <p>FastAPI Wiki 변환·수정 결과(wikiChanges)를 Spring Boot가 검증한 뒤 이 엔티티에 반영합니다.
 */
@Entity
@Table(name = "wiki")
public class Wiki {

    private static final int TITLE_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wiki_id")
    private Long id;

    @Column(name = "wiki_category_id", nullable = false)
    private long wikiCategoryId;

    @Column(name = "scope_key", nullable = false, length = 255)
    private String scopeKey;

    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(name = "wiki_path", nullable = false, length = 500)
    private String wikiPath;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "search_indexed_hash", length = 64)
    private String searchIndexedHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "wiki_refs", nullable = false, columnDefinition = "json")
    private List<Long> wikiRefs = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document_refs", nullable = false, columnDefinition = "json")
    private List<Long> documentRefs = List.of();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Wiki() {
    }

    private Wiki(String scopeKey, long wikiCategoryId, String title) {
        this.scopeKey = requireText(scopeKey, "scopeKey는 필수입니다.");
        this.wikiCategoryId = wikiCategoryId;
        this.title = requireTitle(title);
        // wiki_path는 발급된 wiki_id로 정해지므로 저장 직후 assignStoragePath()로 채운다.
        this.wikiPath = "";
        this.contentHash = "";
    }

    public static Wiki create(String scopeKey, long wikiCategoryId, String title) {
        return new Wiki(scopeKey, wikiCategoryId, title);
    }

    /**
     * 저장으로 wiki_id가 발급된 뒤 본문 파일 경로를 확정합니다.
     */
    public String assignStoragePath() {
        if (id == null) {
            throw new IllegalStateException("wiki_id가 발급된 뒤에 본문 경로를 지정할 수 있습니다.");
        }
        this.wikiPath = storagePathOf(scopeKey, id);
        return this.wikiPath;
    }

    public String assignStoragePath(String wikiPath) {
        String normalized = requireText(wikiPath, "wikiPath는 필수입니다.");
        String expectedPrefix = "wiki/" + scopeKey + "/pages/";
        if (!normalized.startsWith(expectedPrefix) || !normalized.endsWith(".md") || normalized.contains("..")) {
            throw new IllegalArgumentException("wikiPath는 해당 scope의 pages Markdown 경로여야 합니다.");
        }
        this.wikiPath = normalized;
        return this.wikiPath;
    }

    public static String storagePathOf(String scopeKey, long wikiId) {
        return "wiki/" + scopeKey + "/pages/" + wikiId + ".md";
    }

    public void changeTitle(String title) {
        this.title = requireTitle(title);
    }

    public void changeSummary(String summary) {
        String normalized = summary == null ? null : summary.strip();
        if (normalized != null && normalized.length() > 500) {
            throw new IllegalArgumentException("Wiki 요약은 500자를 넘을 수 없습니다.");
        }
        this.summary = normalized == null || normalized.isEmpty() ? null : normalized;
    }

    public void changeContentHash(String contentHash) {
        this.contentHash = requireHash(contentHash, "contentHash");
    }

    public void markSearchIndexed() {
        this.searchIndexedHash = this.contentHash;
    }

    public void changeCategory(long wikiCategoryId) {
        this.wikiCategoryId = wikiCategoryId;
    }

    /**
     * 변환 근거로 쓰인 원본문서를 참조 목록에 더합니다. 이미 있으면 그대로 둡니다.
     */
    public void addDocumentRefs(List<Long> documentIds) {
        List<Long> merged = new ArrayList<>(documentRefs);
        for (Long documentId : documentIds) {
            if (documentId != null && !merged.contains(documentId)) {
                merged.add(documentId);
            }
        }
        this.documentRefs = List.copyOf(merged);
    }

    /**
     * 이 범위에서 빠진 원본문서를 참조 목록에서 지웁니다. (DR-014 하드 삭제, FR-DOC-008 범위 변경)
     */
    public void removeDocumentRef(long documentId) {
        this.documentRefs = documentRefs.stream()
                .filter(documentRef -> documentRef != documentId)
                .toList();
    }

    public void addWikiRef(long targetWikiId) {
        if (targetWikiId == idValue()) {
            throw new IllegalArgumentException("Wiki는 자기 자신을 참조할 수 없습니다.");
        }
        if (wikiRefs.contains(targetWikiId)) {
            return;
        }
        List<Long> merged = new ArrayList<>(wikiRefs);
        merged.add(targetWikiId);
        this.wikiRefs = List.copyOf(merged);
    }

    public void removeWikiRef(long targetWikiId) {
        this.wikiRefs = wikiRefs.stream()
                .filter(wikiRef -> wikiRef != targetWikiId)
                .toList();
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

    public long wikiCategoryId() {
        return wikiCategoryId;
    }

    public String scopeKey() {
        return scopeKey;
    }

    public String title() {
        return title;
    }

    public String wikiPath() {
        return wikiPath;
    }

    /**
     * 본문 파일명에서 확장자를 뺀 페이지 키입니다. 에이전트가 본문 내부 링크를
     * {@code pages/{pageKey}.md} 로 쓰므로, 화면이 링크를 wiki_id 로 되돌릴 때 이 값이 필요합니다.
     * 경로가 아직 확정되지 않았으면 wiki_id 문자열로 대신합니다(구형 경로와 같은 값).
     */
    public String pageKey() {
        if (wikiPath == null || wikiPath.isBlank()) {
            return String.valueOf(idValue());
        }
        String fileName = wikiPath.substring(wikiPath.lastIndexOf('/') + 1);
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }

    public String summary() {
        return summary;
    }

    public String contentHash() {
        return contentHash;
    }

    public String searchIndexedHash() {
        return searchIndexedHash;
    }

    public List<Long> wikiRefs() {
        return List.copyOf(wikiRefs);
    }

    public List<Long> documentRefs() {
        return List.copyOf(documentRefs);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean belongsToScope(String scopeKey) {
        return this.scopeKey.equals(scopeKey);
    }

    private long idValue() {
        return id == null ? 0L : id;
    }

    private static String requireTitle(String title) {
        String trimmed = requireText(title, "Wiki 제목을 입력해주세요.");
        if (trimmed.length() > TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException("Wiki 제목은 200자 이하로 입력해주세요.");
        }
        return trimmed;
    }

    private static String requireHash(String hash, String fieldName) {
        if (hash == null || !hash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(fieldName + "는 SHA-256 64자리 16진수여야 합니다.");
        }
        return hash.toLowerCase(java.util.Locale.ROOT);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
