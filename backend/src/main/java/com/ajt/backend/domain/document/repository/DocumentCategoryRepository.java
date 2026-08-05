package com.ajt.backend.domain.document.repository;

import com.ajt.backend.domain.document.model.DocumentCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 문서 카테고리를 DB에서 찾는 저장소입니다.
 * scopeKey별 목록 조회와 같은 Wiki 공간 안의 이름 중복 확인에 사용합니다.
 */
public interface DocumentCategoryRepository extends JpaRepository<DocumentCategory, Long> {

    List<DocumentCategory> findAllByScopeKeyOrderByNameAsc(String scopeKey);

    boolean existsByScopeKeyAndName(String scopeKey, String name);

    Optional<DocumentCategory> findByScopeKeyAndName(String scopeKey, String name);

    boolean existsByScopeKeyAndNameAndIdNot(String scopeKey, String name, Long id);

    void deleteAllByScopeKey(String scopeKey);
}
