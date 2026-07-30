package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.model.WikiSearchChunk;
import com.ajt.backend.domain.wiki.repository.WikiSearchChunkRepository;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Wiki 검색 청크 색인")
class WikiSearchIndexerTest {

    private final WikiSearchChunkRepository chunkRepository = org.mockito.Mockito.mock(WikiSearchChunkRepository.class);
    private final WikiSearchIndexer indexer = new WikiSearchIndexer(chunkRepository);

    @Test
    @DisplayName("헤더 경로별 청크를 교체하고 Wiki의 본문·색인 해시를 동기화한다")
    void replacesChunksAndSynchronizesWikiHashes() throws Exception {
        Wiki wiki = Wiki.create("ALL", 10L, "휴가 규정");
        assignId(wiki, 101L);
        String markdown = "# 휴가 규정\n\n연차는 15일입니다.\n\n## 반차\n\n반차는 4시간입니다.";

        indexer.replace(wiki, markdown);

        then(chunkRepository).should().deleteByWikiId(101L);
        ArgumentCaptor<List<WikiSearchChunk>> chunks = ArgumentCaptor.forClass(List.class);
        then(chunkRepository).should().saveAll(chunks.capture());
        assertThat(chunks.getValue()).extracting(
                WikiSearchChunk::wikiId,
                WikiSearchChunk::scopeKey,
                WikiSearchChunk::chunkIndex,
                WikiSearchChunk::breadcrumb,
                WikiSearchChunk::content
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                        101L, "ALL", 0L, "휴가 규정", "# 휴가 규정\n\n연차는 15일입니다."
                ),
                org.assertj.core.groups.Tuple.tuple(
                        101L, "ALL", 1L, "휴가 규정 > 반차", "## 반차\n\n반차는 4시간입니다."
                )
        );
        assertThat(chunks.getValue()).allSatisfy(chunk -> assertThat(chunk.contentHash()).hasSize(64));
        assertThat(wiki.contentHash()).hasSize(64);
        assertThat(wiki.searchIndexedHash()).isEqualTo(wiki.contentHash());
    }

    @Test
    @DisplayName("Wiki 삭제 전에 해당 Wiki의 검색 청크를 제거한다")
    void removesChunksForDeletedWiki() {
        indexer.deleteByWikiId(101L);

        then(chunkRepository).should().deleteByWikiId(101L);
    }

    private void assignId(Wiki wiki, long id) throws ReflectiveOperationException {
        Field idField = Wiki.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(wiki, id);
    }
}
