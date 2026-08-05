package com.ajt.backend.domain.document;

import com.ajt.backend.domain.document.model.DocumentCategory;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.repository.DocumentCategoryRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새 부서가 생기면 그 부서 문서 공간에 기본 문서 카테고리를 채워 넣는 컴포넌트입니다(S15P11B106-257).
 *
 * <p>부서 생성 시 호출되어 부서별 문서 공간(wiki_scope)과 기본 카테고리
 * (업무 가이드·규정·정책·회의·공지·기타 자료)를 보장한다. 이미 있으면 중복 생성하지 않는다(idempotent).
 * 카테고리는 특별 취급 없는 일반 카테고리라, 관리자가 이후에 이름을 바꾸거나 삭제할 수 있다.
 *
 * <p>카테고리 목록 조회(CAT-01)는 해당 scope의 wiki_scope 행이 있어야 하므로
 * ({@code DocumentCategoryService}), 기본 카테고리와 함께 부서 문서 공간(wiki_scope)도 보장한다.
 * ERD는 변경하지 않고 데이터로만 기본값을 보장한다.
 */
@Component
public class DefaultDocumentCategoryEnsurer {

    /**
     * 부서가 생기면 기본으로 채워 넣는 문서 카테고리 이름입니다.
     * 화면에는 이름순으로 정렬되어 나오므로 여기의 순서는 노출 순서와 무관하다.
     */
    public static final List<String> DEFAULT_CATEGORY_NAMES =
            List.of("업무 가이드", "규정·정책", "회의·공지", "기타 자료");

    private static final Logger log = LoggerFactory.getLogger(DefaultDocumentCategoryEnsurer.class);

    private final WikiScopeRepository wikiScopeRepository;
    private final DocumentCategoryRepository documentCategoryRepository;

    public DefaultDocumentCategoryEnsurer(
            WikiScopeRepository wikiScopeRepository,
            DocumentCategoryRepository documentCategoryRepository
    ) {
        this.wikiScopeRepository = wikiScopeRepository;
        this.documentCategoryRepository = documentCategoryRepository;
    }

    /**
     * 부서 문서 공간과 기본 문서 카테고리를 보장합니다. 이미 있는 것은 그대로 두고 없는 것만 채웁니다
     * (중복 생성 없음). 부서 생성과 같은 트랜잭션에서 실행되도록 REQUIRED로 참여한다.
     */
    @Transactional
    public void ensureForDepartment(long departmentId) {
        WikiScope departmentScope = WikiScope.department(List.of(departmentId));
        String scopeKey = departmentScope.scopeKey();
        if (!wikiScopeRepository.existsById(scopeKey)) {
            wikiScopeRepository.save(departmentScope);
        }

        int created = 0;
        for (String name : DEFAULT_CATEGORY_NAMES) {
            if (!documentCategoryRepository.existsByScopeKeyAndName(scopeKey, name)) {
                documentCategoryRepository.save(DocumentCategory.create(scopeKey, name, null));
                created++;
            }
        }
        if (created > 0) {
            log.info("부서(id={}) 기본 문서 카테고리 {}개 생성(scope={})", departmentId, created, scopeKey);
        }
    }
}
