package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한쪽에만 저장된 Wiki-Wiki 참조를 대칭으로 만듭니다.
 *
 * <p>Wiki 관계는 무방향이고(FR-WIKI-010) 양쪽 JSON 에 저장해야 하는데(DR-003), 반영 경로가
 * source 에만 쓰던 기간이 있었습니다. 그 기간에 쌓인 반쪽 관계는 저장을 고쳐도 스스로 낫지
 * 않으므로 한 번 채워야 합니다.
 *
 * <p><b>이미 있는 참조만 대칭으로 만듭니다 — 새 관계를 만들지 않습니다.</b> 그래서 몇 번
 * 돌려도 결과가 같고(idempotent), 기동마다 실행해도 안전합니다.
 */
@Service
public class WikiRelationRepairService {

    private final WikiRepository wikiRepository;

    public WikiRelationRepairService(WikiRepository wikiRepository) {
        this.wikiRepository = wikiRepository;
    }

    /**
     * @return 반대쪽에 채운 참조 건수
     */
    @Transactional
    public int repairAsymmetricRelations() {
        Map<Long, Wiki> wikisById = new LinkedHashMap<>();
        for (Wiki wiki : wikiRepository.findAll()) {
            wikisById.put(wiki.id(), wiki);
        }

        int filled = 0;
        for (Wiki source : wikisById.values()) {
            for (Long targetId : source.wikiRefs()) {
                Wiki target = wikisById.get(targetId);
                // 사라진 Wiki 를 가리키는 참조는 이 보정의 대상이 아니다.
                // 다른 공간 침범도 채우지 않는다 — 범위를 넘는 관계는 애초에 무효다.
                if (target == null || !target.scopeKey().equals(source.scopeKey())) {
                    continue;
                }
                if (target.wikiRefs().contains(source.id())) {
                    continue;
                }
                target.addWikiRef(source.id());
                filled++;
            }
        }
        return filled;
    }
}
