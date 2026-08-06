package com.ajt.backend.global.config;

import com.ajt.backend.domain.document.service.StuckAiJobRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 애플리케이션 시작 시 중단된 AI 작업을 한 번 마감합니다(S15P11B106-294).
 *
 * <p>PROCESSING 은 워커 스레드가 메모리에서 들고 있는 상태다. 그 도중 프로세스가 죽으면 이어받을
 * 주체가 없어 영구히 PROCESSING 으로 남고, 그 작업에 속한 문서는 수정·삭제가 막힌다
 * (S15P11B106-286). 기동 시점에는 워커가 아직 없으므로 정상 진행 중인 작업을 잘못 건드릴 수 없다.
 *
 * <p>대상이 없으면 아무 일도 하지 않으므로 매 기동 시 실행해도 안전하다(idempotent).
 */
@Configuration
public class StuckAiJobRecoveryConfig {

    private static final Logger log = LoggerFactory.getLogger(StuckAiJobRecoveryConfig.class);

    @Bean
    ApplicationRunner failInterruptedAiJobs(StuckAiJobRecoveryService recoveryService) {
        return args -> {
            int failed = recoveryService.failInterruptedJobs();
            if (failed > 0) {
                log.warn("서버 재시작으로 중단된 AI 작업 {}건을 실패로 마감했습니다. "
                        + "해당 문서는 「AI 작업 요약」에서 재시도할 수 있습니다.", failed);
            } else {
                log.info("중단된 AI 작업 점검 완료: 마감 대상 없음");
            }
        };
    }
}
