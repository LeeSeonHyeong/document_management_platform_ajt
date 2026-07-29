package com.ajt.backend.global.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 인증번호 저장소의 만료·시도횟수 제한 동작 검증.
 */
class PasswordResetCodeStoreTest {

    private MutableClock clock;
    private PasswordResetCodeStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-07-29T00:00:00Z"));
        store = new PasswordResetCodeStore(clock);
    }

    @Test
    @DisplayName("저장한 인증번호와 값이 같으면 일치한다")
    void matchesValidCode() {
        store.save("employee@ajt.com", "123456");
        assertThat(store.matches("employee@ajt.com", "123456")).isTrue();
    }

    @Test
    @DisplayName("틀린 인증번호를 5회 시도하면 코드가 폐기되어 이후 올바른 코드도 실패한다")
    void invalidatesAfterMaxAttempts() {
        store.save("employee@ajt.com", "123456");
        for (int i = 0; i < 5; i++) {
            assertThat(store.matches("employee@ajt.com", "000000")).isFalse();
        }
        assertThat(store.matches("employee@ajt.com", "123456")).isFalse();
    }

    @Test
    @DisplayName("만료 시간이 지나면 올바른 코드도 실패한다")
    void failsWhenExpired() {
        store.save("employee@ajt.com", "123456");
        clock.advance(Duration.ofMinutes(6));
        assertThat(store.matches("employee@ajt.com", "123456")).isFalse();
    }

    @Test
    @DisplayName("remove 후에는 올바른 코드도 실패한다")
    void failsAfterRemove() {
        store.save("employee@ajt.com", "123456");
        store.remove("employee@ajt.com");
        assertThat(store.matches("employee@ajt.com", "123456")).isFalse();
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
