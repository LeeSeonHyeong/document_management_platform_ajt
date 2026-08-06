package com.ajt.backend.domain.document.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 작업 시각이 서버 시간대와 무관한지 고정합니다(S15P11B106-276).
 *
 * <p>예전에는 {@code LocalDateTime.now()}를 썼다. 시간대가 없는 벽시계 값이라 JVM 기본
 * 시간대를 그대로 따랐고, 응답에도 시간대 표기가 없어 브라우저가 자기 시간대로 해석했다.
 * 배포 컨테이너는 TZ 설정이 없어 UTC로 돌기 때문에, UTC 벽시계를 KST로 읽어 화면에
 * 9시간 과거로 표시됐다.
 *
 * <p>{@link Instant}는 절대 시각이라 이 문제가 생기지 않는다. 이 테스트는 시각 필드가
 * 다시 시간대 없는 타입으로 돌아가는 것을 막는다.
 */
@DisplayName("AI 작업 시각")
class AiJobTimestampTest {

    @Test
    @DisplayName("시작·종료 시각은 절대 시각(Instant)이라 시간대 해석이 끼어들지 않는다")
    void tracksInstants() {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/abc", List.of(15L));

        Instant beforeStart = Instant.now();
        job.start();
        Instant afterStart = Instant.now();

        assertThat(job.startedAt()).isBetween(beforeStart, afterStart);

        Instant beforeFinish = Instant.now();
        job.finish(List.of(AiJob.DocumentParseResult.succeeded(15L, "rule.md", "요약")));
        Instant afterFinish = Instant.now();

        assertThat(job.finishedAt()).isBetween(beforeFinish, afterFinish);
        assertThat(job.status()).isEqualTo(AiJobStatus.COMPLETED);
    }

    @Test
    @DisplayName("소요 시간은 시작·종료 시각의 차이로 계산할 수 있다")
    void measuresDuration() {
        AiJob job = AiJob.waiting(10L, "ALL", "ALL/jobs/abc", List.of(15L));
        job.start();
        job.finish(List.of(AiJob.DocumentParseResult.succeeded(15L, "rule.md", "요약")));

        assertThat(job.finishedAt()).isAfterOrEqualTo(job.startedAt());
    }
}
