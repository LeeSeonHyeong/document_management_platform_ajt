package com.ajt.backend.domain.wiki.repository;

import com.ajt.backend.domain.wiki.model.WikiSearchChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Wiki 본문에서 파생한 전문 검색 청크 저장소입니다. */
public interface WikiSearchChunkRepository extends JpaRepository<WikiSearchChunk, Long> {

    /**
     * 수정(S15P11B106-173): 파생 삭제에서 <b>벌크 삭제</b>로 바꿉니다.
     *
     * <p>파생 삭제는 DELETE 를 영속성 컨텍스트의 액션 큐에 쌓기만 하고, Hibernate 는 flush 때
     * <b>INSERT 를 DELETE 보다 먼저</b> 실행한다. 그래서 재색인
     * ({@code WikiSearchIndexer.replace})에서 옛 청크가 아직 있는 상태로 새 청크가 들어가
     * {@code uk_wiki_search_chunk (wiki_id, chunk_index)} 에 걸렸다.
     *
     * <p>{@code flushAutomatically} 로 앞선 변경을 먼저 반영하고 DELETE 를 곧바로 DB 에 보낸다.
     * <b>{@code clearAutomatically} 는 쓰지 않는다</b> — 컨텍스트를 비우면 호출자가 들고 있는
     * {@code Wiki} 가 detached 가 되어 뒤따르는 {@code changeContentHash}·{@code markSearchIndexed}
     * 가 저장되지 않는다.
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("delete from WikiSearchChunk chunk where chunk.wikiId = :wikiId")
    void deleteByWikiId(@Param("wikiId") long wikiId);

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
