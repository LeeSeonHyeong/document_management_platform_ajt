package com.ajt.backend.domain.wiki.repository;

import com.ajt.backend.domain.wiki.model.WikiCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Wiki 카테고리를 DB에서 찾는 저장소입니다.
 * FastAPI Wiki 변환 요청의 currentCategories를 구성할 때 scopeKey 전체 목록을 읽습니다.
 */
public interface WikiCategoryRepository extends JpaRepository<WikiCategory, Long> {

    List<WikiCategory> findAllByScopeKeyOrderByNameAsc(String scopeKey);

    boolean existsByScopeKeyAndName(String scopeKey, String name);
}
