package com.ajt.backend.domain.wiki.repository;

import com.ajt.backend.domain.wiki.model.Wiki;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Wiki를 DB에서 찾는 저장소입니다.
 * FastAPI 문맥 선택 결과를 Spring Boot가 재검증할 때, 그리고 목차를 다시 쓸 때 scopeKey 단위로 읽습니다.
 */
public interface WikiRepository extends JpaRepository<Wiki, Long>, JpaSpecificationExecutor<Wiki> {

    List<Wiki> findAllByScopeKey(String scopeKey);

    List<Wiki> findAllByScopeKeyAndIdIn(String scopeKey, List<Long> ids);

    /**
     * 여러 공간의 Wiki 개수를 한 번의 GROUP BY 쿼리로 집계합니다.
     * 공간 목록 조회에서 공간별 count 쿼리를 반복(N+1)하지 않도록 사용합니다.
     * Wiki가 없는 공간은 결과에 포함되지 않으므로 호출부에서 0으로 보정합니다.
     */
    @Query("""
            select w.scopeKey as scopeKey, count(w) as wikiCount
            from Wiki w
            where w.scopeKey in :scopeKeys
            group by w.scopeKey
            """)
    List<ScopeWikiCount> countByScopeKeys(@Param("scopeKeys") Collection<String> scopeKeys);

    boolean existsByWikiCategoryId(long wikiCategoryId);

    void deleteAllByScopeKey(String scopeKey);

    /**
     * 공간별 Wiki 개수 집계 결과 프로젝션입니다.
     */
    interface ScopeWikiCount {
        String getScopeKey();

        long getWikiCount();
    }
}
