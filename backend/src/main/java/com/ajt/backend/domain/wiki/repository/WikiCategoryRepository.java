package com.ajt.backend.domain.wiki.repository;

import com.ajt.backend.domain.wiki.model.WikiCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Wiki 카테고리를 DB에서 찾는 저장소입니다.
 *
 * <p>수정(S15P11B106-174): Wiki 변환 요청의 {@code currentCategories}를 구성하던 용도가 없어졌다 —
 * 에이전트가 카테고리 조회 API로 직접 읽는다. 지금 scopeKey 전체 목록을 읽는 곳은 Wiki 조회
 * 창구와 공개 조회다.
 */
public interface WikiCategoryRepository extends JpaRepository<WikiCategory, Long> {

    List<WikiCategory> findAllByScopeKeyOrderByNameAsc(String scopeKey);

    boolean existsByScopeKeyAndName(String scopeKey, String name);

    void deleteAllByScopeKey(String scopeKey);
}
