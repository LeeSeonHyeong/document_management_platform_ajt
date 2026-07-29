package com.ajt.backend.domain.wiki.repository;

import com.ajt.backend.domain.wiki.model.WikiChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Wiki 관리자 대화를 DB에서 찾는 저장소입니다.
 * 조회와 FastAPI 수정 요청의 chatHistory 구성에 모두 오래된 순서를 사용합니다.
 */
public interface WikiChatMessageRepository extends JpaRepository<WikiChatMessage, Long> {

    List<WikiChatMessage> findAllByWikiIdOrderByCreatedAtAscIdAsc(Long wikiId);
}
