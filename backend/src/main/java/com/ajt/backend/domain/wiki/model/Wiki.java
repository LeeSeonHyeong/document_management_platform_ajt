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
// TODO(DB): FastAPI 내부 API 계약은 selectedWikis[].summary를 필수로 요구하지만 wiki 테이블에 summary 컬럼이 없다.
//           현재는 index.md 항목에서 요약을 되읽어 채우고 없으면 제목으로 대체한다. 이 방식은 목차 파일 형식에 의존한다.
//           wiki.summary VARCHAR(500) 추가가 정본 해결책이다. erd.sql 변경 필요 — 팀원 합의 후 진행. (임의 변경 금지)
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

    public static String storagePathOf(String scopeKey, long wikiId) {
        return "wiki/" + scopeKey + "/pages/" + wikiId + ".md";
    }

    public void changeTitle(String title) {
        this.title = requireTitle(title);
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

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
