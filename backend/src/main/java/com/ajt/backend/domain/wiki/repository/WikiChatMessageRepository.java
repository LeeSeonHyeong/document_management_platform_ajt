package com.ajt.backend.domain.wiki.repository;

import com.ajt.backend.domain.wiki.model.WikiChatMessage;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Wiki 관리자 대화를 DB에서 찾는 저장소입니다.
 * 조회와 FastAPI 수정 요청의 chatHistory 구성에 모두 오래된 순서를 사용합니다.
 *
 * <p>대화는 위키(wiki_id) 단위로 저장되지만(FR-AI-004), 조회는 같은 scope(부서)의 위키를
 * 넘나들며 이어져야 한다(S15P11B106-220) — 그래서 단일 wikiId가 아니라 같은 scope에 속한
 * wiki_id 전체로 조회한다.
 */
public interface WikiChatMessageRepository extends JpaRepository<WikiChatMessage, Long> {

    List<WikiChatMessage> findAllByWikiIdInOrderByCreatedAtAscIdAsc(Collection<Long> wikiIds);
}
