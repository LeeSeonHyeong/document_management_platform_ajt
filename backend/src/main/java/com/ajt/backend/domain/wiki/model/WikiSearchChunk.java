package com.ajt.backend.domain.wiki.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** Wiki Markdown 본문에서 파생한 전문 검색 청크입니다. */
@Entity
@Table(name = "wiki_search_chunk", uniqueConstraints = {
        @UniqueConstraint(name = "uk_wiki_search_chunk", columnNames = {"wiki_id", "chunk_index"})
})
public class WikiSearchChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chunk_id")
    private Long id;

    @Column(name = "wiki_id", nullable = false)
    private long wikiId;

    @Column(name = "scope_key", nullable = false, length = 255)
    private String scopeKey;

    @Column(name = "chunk_index", nullable = false)
    private long chunkIndex;

    @Column(name = "breadcrumb", length = 500)
    private String breadcrumb;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    protected WikiSearchChunk() {
    }

    private WikiSearchChunk(
            long wikiId,
            String scopeKey,
            long chunkIndex,
            String breadcrumb,
            String content,
            String contentHash
    ) {
        this.wikiId = wikiId;
        this.scopeKey = scopeKey;
        this.chunkIndex = chunkIndex;
        this.breadcrumb = breadcrumb;
        this.content = content;
        this.contentHash = contentHash;
    }

    public static WikiSearchChunk create(
            long wikiId,
            String scopeKey,
            long chunkIndex,
            String breadcrumb,
            String content,
            String contentHash
    ) {
        return new WikiSearchChunk(wikiId, scopeKey, chunkIndex, breadcrumb, content, contentHash);
    }

    @PrePersist
    void prePersist() {
        indexedAt = Instant.now();
    }

    public Long id() {
        return id;
    }

    public long wikiId() {
        return wikiId;
    }

    public String scopeKey() {
        return scopeKey;
    }

    public long chunkIndex() {
        return chunkIndex;
    }

    public String breadcrumb() {
        return breadcrumb;
    }

    public String content() {
        return content;
    }

    public String contentHash() {
        return contentHash;
    }

    public Instant indexedAt() {
        return indexedAt;
    }
}
