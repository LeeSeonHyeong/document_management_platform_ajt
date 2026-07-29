package com.ajt.backend.global.auth;

import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 재설정 요청 rate limit입니다. (이메일·IP 등 키 기준 고정 창 카운터)
 *
 * <p>10분 창에서 키당 최대 5회 요청을 허용하고, 초과하면 429를 던진다.
 * 무차별 요청·이메일 폭탄과 계정 존재 여부 탐색을 완화하기 위함이다. (인메모리, 서버 재시작 시 초기화)
 */
@Component
public class PasswordResetRateLimiter {

    private static final int MAX_REQUESTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public PasswordResetRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 키의 요청 횟수를 1 늘린다. 창 안에서 한도를 넘으면 429(TOO_MANY_REQUESTS)를 던진다. */
    public void check(String key) {
        Instant now = clock.instant();
        boolean[] over = {false};
        windows.compute(key, (k, window) -> {
            if (window == null || now.isAfter(window.resetAt)) {
                return new Window(now.plus(WINDOW));
            }
            window.count++;
            if (window.count > MAX_REQUESTS) {
                over[0] = true;
            }
            return window;
        });
        if (over[0]) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    private static final class Window {
        private final Instant resetAt;
        private int count;

        private Window(Instant resetAt) {
            this.resetAt = resetAt;
            this.count = 1;
        }
    }
}
