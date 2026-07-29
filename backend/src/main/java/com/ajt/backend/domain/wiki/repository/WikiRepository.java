package com.ajt.backend.domain.wiki.repository;

import com.ajt.backend.domain.wiki.model.Wiki;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Wiki를 DB에서 찾는 저장소입니다.
 * FastAPI 문맥 선택 결과를 Spring Boot가 재검증할 때, 그리고 목차를 다시 쓸 때 scopeKey 단위로 읽습니다.
 */
public interface WikiRepository extends JpaRepository<Wiki, Long> {

    List<Wiki> findAllByScopeKey(String scopeKey);

    List<Wiki> findAllByScopeKeyAndIdIn(String scopeKey, List<Long> ids);

    boolean existsByWikiCategoryId(long wikiCategoryId);
}
