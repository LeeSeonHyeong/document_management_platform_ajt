package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiCategory;
import com.ajt.backend.domain.wiki.model.WikiSearchChunk;
import com.ajt.backend.domain.wiki.repository.WikiCategoryRepository;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.repository.WikiSearchChunkRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재색인 회귀(S15P11B106-173).
 *
 * <p>`replace` 가 옛 청크를 지우고 새 청크를 넣는데, 파생 삭제는 DELETE 를 액션 큐에 쌓기만 한다.
 * Hibernate 는 flush 때 <b>INSERT 를 DELETE 보다 먼저</b> 실행하므로 옛 청크가 아직 있는 상태로
 * 새 청크가 들어가 {@code uk_wiki_search_chunk (wiki_id, chunk_index)} 에 걸렸다.
 *
 * <p>티켓은 「MySQL 이어야 재현된다」고 적었지만 <b>그렇지 않다.</b> 유니크 제약이
 * {@code WikiSearchChunk} 엔티티에 선언돼 있어 {@code ddl-auto=create-drop} 이면 H2 에도 생긴다.
 * 실환경에서 안 걸렸던 이유는 로컬 DB 가 기동마다 비어 갱신 대상에 옛 청크가 없었던 것이지,
 * H2 가 제약을 못 만들어서가 아니다. 그래서 이 테스트는 <b>같은 트랜잭션에서 두 번 색인</b>해
 * 결함을 그대로 재현한다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ajt.local-data.enabled=false"
})
@Transactional
@DisplayName("Wiki 검색 청크 재색인")
class WikiSearchIndexerReindexTest {

    private final WikiSearchIndexer indexer;
    private final WikiRepository wikiRepository;
    private final WikiCategoryRepository wikiCategoryRepository;
    private final WikiSearchChunkRepository chunkRepository;
    private final EntityManager entityManager;

    @Autowired
    WikiSearchIndexerReindexTest(
            WikiSearchIndexer indexer,
            WikiRepository wikiRepository,
            WikiCategoryRepository wikiCategoryRepository,
            WikiSearchChunkRepository chunkRepository,
            EntityManager entityManager
    ) {
        this.indexer = indexer;
        this.wikiRepository = wikiRepository;
        this.wikiCategoryRepository = wikiCategoryRepository;
        this.chunkRepository = chunkRepository;
        this.entityManager = entityManager;
    }

    @Test
    @DisplayName("이미 색인된 Wiki를 다시 색인해도 중복 키로 실패하지 않는다")
    void reindexingAnExistingWikiDoesNotCollideOnTheUniqueKey() {
        Wiki wiki = persistedWiki();

        indexer.replace(wiki, "# 휴가 규정\n첫 본문\n\n## 연차\n15일\n\n## 반차\n반나절");
        entityManager.flush();
        long firstCount = chunkRepository.count();
        assertThat(firstCount).isGreaterThan(1);

        // 두 번째 문서가 같은 Wiki 를 갱신하는 상황이다. 여기서 터졌다.
        indexer.replace(wiki, "# 휴가 규정\n둘째 본문\n\n## 연차\n20일");
        entityManager.flush();

        List<WikiSearchChunk> chunks = chunkRepository.findAll();
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content()).doesNotContain("첫 본문"));
        assertThat(chunks).anySatisfy(chunk ->
                assertThat(chunk.content()).contains("둘째 본문"));
    }

    @Test
    @DisplayName("재색인 뒤 청크 수는 새 본문 기준이다 — 옛 청크가 남지 않는다")
    void chunkCountFollowsTheNewBody() {
        Wiki wiki = persistedWiki();

        indexer.replace(wiki, "# 제목\n본문\n\n## 하나\n1\n\n## 둘\n2\n\n## 셋\n3");
        entityManager.flush();

        indexer.replace(wiki, "# 제목\n본문만 남긴다");
        entityManager.flush();

        assertThat(chunkRepository.count()).isEqualTo(1);
    }

    private Wiki persistedWiki() {
        WikiCategory category = wikiCategoryRepository.save(WikiCategory.create("ALL", "인사·복무", null));
        Wiki wiki = wikiRepository.save(Wiki.create("ALL", category.id(), "휴가 규정"));
        wiki.assignStoragePath();
        return wikiRepository.save(wiki);
    }
}
