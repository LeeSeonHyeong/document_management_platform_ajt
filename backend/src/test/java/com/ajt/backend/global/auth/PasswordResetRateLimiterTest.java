package com.ajt.backend.global.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 비밀번호 재설정 요청 rate limit 동작 검증.
 */
class PasswordResetRateLimiterTest {

    private MutableClock clock;
    private PasswordResetRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-07-29T00:00:00Z"));
        rateLimiter = new PasswordResetRateLimiter(clock);
    }

    @Test
    @DisplayName("창 안에서 5회까지 허용하고 6회째 요청은 429를 던진다")
    void blocksAfterLimit() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.check("email:employee@ajt.com");
        }
        assertThatThrownBy(() -> rateLimiter.check("email:employee@ajt.com"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("창이 지나면 카운트가 초기화되어 다시 허용한다")
    void resetsAfterWindow() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.check("email:employee@ajt.com");
        }
        clock.advance(Duration.ofMinutes(11));
        assertThatCode(() -> rateLimiter.check("email:employee@ajt.com")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("키가 다르면 서로 카운트가 분리된다")
    void separateKeys() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.check("ip:1.1.1.1");
        }
        assertThatCode(() -> rateLimiter.check("ip:2.2.2.2")).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
