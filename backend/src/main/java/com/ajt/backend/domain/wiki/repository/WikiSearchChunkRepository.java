package com.ajt.backend.domain.wiki.repository;

import com.ajt.backend.domain.wiki.model.WikiSearchChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Wiki 본문에서 파생한 전문 검색 청크 저장소입니다. */
public interface WikiSearchChunkRepository extends JpaRepository<WikiSearchChunk, Long> {

    @Transactional
    void deleteByWikiId(long wikiId);

    @Query(value = """
            select * from wiki_search_chunk
            where scope_key = :scopeKey
              and match(content) against (:query in boolean mode)
            order by match(content) against (:query in boolean mode) desc, wiki_id asc, chunk_index asc
            limit :limit
            """, nativeQuery = true)
    List<WikiSearchChunk> searchByScopeKey(
            @Param("scopeKey") String scopeKey,
            @Param("query") String query,
            @Param("limit") int limit
    );
}
