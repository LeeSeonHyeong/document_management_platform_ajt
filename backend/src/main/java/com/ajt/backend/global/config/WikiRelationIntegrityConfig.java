package com.ajt.backend.global.config;

import com.ajt.backend.domain.wiki.service.WikiRelationRepairService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 애플리케이션 시작 시 Wiki-Wiki 관계의 대칭성을 한 번 점검·보정합니다.
 *
 * <p>관계를 양쪽 JSON 에 저장하도록 고치기 전(FR-WIKI-010·DR-003)에 쌓인 반쪽 관계를 채웁니다.
 * 이미 있는 참조만 대칭으로 만들므로 매 기동 시 실행해도 안전하고(idempotent), 보정이 끝난
 * 뒤에는 대상이 없어 실질적인 no-op 이 됩니다.
 */
@Configuration
public class WikiRelationIntegrityConfig {

    private static final Logger log = LoggerFactory.getLogger(WikiRelationIntegrityConfig.class);

    @Bean
    ApplicationRunner repairAsymmetricWikiRelations(WikiRelationRepairService repairService) {
        return args -> {
            int filled = repairService.repairAsymmetricRelations();
            if (filled > 0) {
                log.warn("한쪽에만 저장돼 있던 Wiki 관계 {}건을 대칭으로 채웠습니다.", filled);
            } else {
                log.info("Wiki 관계 대칭성 점검 완료: 보정 대상 없음");
            }
        };
    }
}
