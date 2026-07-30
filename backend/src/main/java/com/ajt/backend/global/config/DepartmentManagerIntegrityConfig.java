package com.ajt.backend.global.config;

import com.ajt.backend.domain.department.DepartmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 애플리케이션 시작 시 부서장 지정 정합성을 한 번 점검·정리합니다.
 *
 * <p>부서장 자동 해제(S15P11B106-58) 도입 이전에 강등·비활성으로 자격을 잃은 채 남아 있는 부서장
 * 지정을 해제합니다. 자격을 유지한 부서장 지정은 건드리지 않으므로 매 기동 시 실행해도 안전합니다
 * (idempotent). 자동 해제가 반영된 뒤에는 정상적으로 해제 대상이 없어 실질적인 no-op이 됩니다.
 */
@Configuration
public class DepartmentManagerIntegrityConfig {

    private static final Logger log = LoggerFactory.getLogger(DepartmentManagerIntegrityConfig.class);

    @Bean
    ApplicationRunner releaseIneligibleDepartmentManagers(DepartmentService departmentService) {
        return args -> {
            int cleared = departmentService.releaseIneligibleDepartmentManagers();
            if (cleared > 0) {
                log.warn("부서장 자격을 잃은 지정 {}건을 자동 해제했습니다.", cleared);
            } else {
                log.info("부서장 지정 정합성 점검 완료: 해제 대상 없음");
            }
        };
    }
}
