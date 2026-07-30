package com.ajt.backend.domain.wiki.repository;

import com.ajt.backend.domain.wiki.model.WikiSearchChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** Wiki 본문에서 파생한 전문 검색 청크 저장소입니다. */
public interface WikiSearchChunkRepository extends JpaRepository<WikiSearchChunk, Long> {

    @Transactional
    void deleteByWikiId(long wikiId);
}
