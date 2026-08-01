package com.ajt.backend.global.ai.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WikiCapabilityServiceTest {

    private final WikiCapabilityService service = new WikiCapabilityService();

    @Test
    void issuesCapabilityBoundToScopeAndVersion() {
        String rawCapability = service.issue("D1-D2", 47L, Duration.ofMinutes(1));

        WikiCapability capability = service.require(rawCapability, "D1-D2");

        assertThat(capability.scopeVersion()).isEqualTo(47L);
        assertThat(capability.scopeKey()).isEqualTo("D1-D2");
    }

    @Test
    void rejectsExpiredCapabilityWithoutRevealingScope() {
        String rawCapability = service.issue("D1-D2", 47L, Duration.ZERO);

        assertThatThrownBy(() -> service.require(rawCapability, "D1-D2"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_CAPABILITY_EXPIRED);
    }

    /**
     * 회귀(S15P11B106-172): 에이전트가 한 턴에 Wiki 조회 API를 <b>동시에</b> 여러 개 부른다.
     * 같은 허가값으로 같은 범위를 부르는데 일부만 만료로 거절되면, AI가 그것을 범위 변경
     * 신호로 기록해 작업 전체를 실패시킨다 — TTL 30분짜리 작업이 30초 만에 죽었다.
     */
    @Test
    void allowsConcurrentChecksWithTheSameCapability() throws Exception {
        String rawCapability = service.issue("D1-D2", 47L, Duration.ofMinutes(10));
        int threads = 16;
        int callsPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        AtomicInteger rejected = new AtomicInteger();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    for (int call = 0; call < callsPerThread; call++) {
                        try {
                            service.require(rawCapability, "D1-D2");
                        } catch (BusinessException expiredByRace) {
                            rejected.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rejected).hasValue(0);
    }

    @Test
    void keepsCapabilityUsableAfterManyChecks() {
        String rawCapability = service.issue("D1-D2", 47L, Duration.ofMinutes(10));

        for (int call = 0; call < 5; call++) {
            service.require(rawCapability, "D1-D2");
        }

        // 회수는 명시적으로만 일어난다 — 검증이 허가값을 소모하지 않는다.
        service.revoke(rawCapability);
        assertThatThrownBy(() -> service.require(rawCapability, "D1-D2"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsCapabilityForAnotherScope() {
        String rawCapability = service.issue("D1-D2", 47L, Duration.ofMinutes(1));

        assertThatThrownBy(() -> service.require(rawCapability, "ALL"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WIKI_CAPABILITY_EXPIRED);
    }
}
