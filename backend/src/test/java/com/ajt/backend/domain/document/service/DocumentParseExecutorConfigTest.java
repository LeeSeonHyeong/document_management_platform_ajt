package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("원본문서 파싱 실행기 설정")
class DocumentParseExecutorConfigTest {

    @Test
    @DisplayName("파싱 실행기는 동시에 두 번째 작업을 시작하지 않는다")
    void createsSingleThreadExecutor() throws Exception {
        ExecutorService executor = new DocumentParseExecutorConfig().documentParseExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                firstStarted.countDown();
                await(releaseFirst);
            });
            executor.submit(secondStarted::countDown);

            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondStarted.await(100, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();

            assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
