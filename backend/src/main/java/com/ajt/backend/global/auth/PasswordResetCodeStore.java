package com.ajt.backend.global.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 재설정 인증번호를 메모리에 잠시 보관하는 저장소입니다.
 *
 * <p>요구사항상 재설정용 코드를 DB에 저장하지 않으므로 인메모리로 관리한다.
 * - 저장 후 5분이 지나면 무효.
 * - 같은 이메일로 다시 요청하면 최신 코드로 덮어씀.
 * - 수정: 틀린 코드를 5회 시도하면 해당 코드를 폐기한다(6자리 무차별 대입 방지).
 */
@Component
public class PasswordResetCodeStore {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 5;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Clock clock;

    public PasswordResetCodeStore(Clock clock) {
        this.clock = clock;
    }

    /** 이메일에 대한 인증번호를 저장한다. 만료 시각은 저장 시점 + 5분이다. */
    public void save(String email, String code) {
        store.put(email, new Entry(code, clock.instant().plus(TTL)));
    }

    /**
     * 저장된 코드가 있고 만료 전이며 값이 일치하면 true.
     * 수정: 값이 틀리면 시도 횟수를 늘리고, 만료·시도초과 시 코드를 폐기한다. (키 단위 원자 처리)
     */
    public boolean matches(String email, String code) {
        boolean[] result = {false};
        store.compute(email, (key, entry) -> {
            if (entry == null) {
                return null;
            }
            if (clock.instant().isAfter(entry.expiresAt)) {
                return null; // 만료 → 폐기
            }
            if (entry.code.equals(code)) {
                result[0] = true;
                return entry; // 일치 → 유지 (변경 성공 시 remove로 폐기)
            }
            entry.attempts++;
            if (entry.attempts >= MAX_ATTEMPTS) {
                return null; // 시도 초과 → 폐기
            }
            return entry;
        });
        return result[0];
    }

    /** 사용 완료(비밀번호 변경 성공)한 코드를 제거한다. */
    public void remove(String email) {
        store.remove(email);
    }

    private static final class Entry {
        private final String code;
        private final Instant expiresAt;
        private int attempts;

        private Entry(String code, Instant expiresAt) {
            this.code = code;
            this.expiresAt = expiresAt;
        }
    }
}
