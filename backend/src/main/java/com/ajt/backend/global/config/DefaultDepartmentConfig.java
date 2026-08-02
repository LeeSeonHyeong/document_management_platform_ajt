package com.ajt.backend.global.config;

import com.ajt.backend.domain.department.DefaultDepartmentEnsurer;
import com.ajt.backend.domain.department.Department;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 애플리케이션 시작 시 시스템 기본 부서('전체')를 항상 보장합니다(S15P11B106-146).
 *
 * <p>더미데이터 로더(LocalDataInitializer, local 프로필 전용)에 종속되지 않도록, 프로필과 무관하게 서버 기동 시
 * 실행되는 별도 러너에서 보장한다. 이미 있으면 중복 생성하지 않는다(idempotent).
 *
 * <p>{@code ajt.default-department.enabled=false}로 끌 수 있다(기본값 실행). 부서 목록 개수를 직접 단언하는
 * 일부 테스트에서만 이 자동 생성을 꺼서 격리한다.
 */
@Configuration
public class DefaultDepartmentConfig {

    private static final Logger log = LoggerFactory.getLogger(DefaultDepartmentConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "ajt.default-department", name = "enabled", havingValue = "true", matchIfMissing = true)
    ApplicationRunner ensureDefaultDepartment(DefaultDepartmentEnsurer defaultDepartmentEnsurer) {
        return args -> {
            defaultDepartmentEnsurer.ensure();
            log.info("시스템 기본 부서('{}') 보장 완료", Department.DEFAULT_NAME);
        };
    }
}
