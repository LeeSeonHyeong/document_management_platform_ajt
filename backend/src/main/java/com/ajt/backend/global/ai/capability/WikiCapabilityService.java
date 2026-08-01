package com.ajt.backend.global.ai.capability;

import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class WikiCapabilityService {

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentMap<String, WikiCapability> capabilities = new ConcurrentHashMap<>();

    public String issue(String scopeKey, long scopeVersion, Duration ttl) {
        if (scopeKey == null || scopeKey.isBlank() || scopeVersion < 0 || ttl == null || ttl.isNegative()) {
            throw new IllegalArgumentException("Wiki capability 발급값이 올바르지 않습니다.");
        }
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawCapability = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        capabilities.put(rawCapability, new WikiCapability(scopeKey, scopeVersion, Instant.now().plus(ttl)));
        return rawCapability;
    }

    /**
     * 허가값을 검증합니다. <b>소모하지 않는다</b> — 회수는 {@link #revoke} 로만 일어난다.
     *
     * <p>수정(S15P11B106-172): 예전에는 {@code remove} 로 꺼내 검사하고 {@code put} 으로
     * 되돌렸다. 그 사이에 들어온 다른 스레드가 {@code null} 을 받아 <b>만료로 오판했다</b> —
     * {@code ConcurrentHashMap} 이어도 두 연산은 원자적이지 않다. 에이전트가 한 턴에 조회 API를
     * 동시에 여러 개 부르면서 같은 허가값의 일부 호출만 404가 났고, AI가 그것을 범위 변경
     * 신호로 기록해 작업 전체를 실패시켰다. 읽기만 하면 경합이 사라진다.
     */
    public WikiCapability require(String rawCapability, String scopeKey) {
        if (rawCapability == null || rawCapability.isBlank() || scopeKey == null || scopeKey.isBlank()) {
            throw new BusinessException(ErrorCode.WIKI_CAPABILITY_EXPIRED);
        }
        WikiCapability capability = capabilities.get(rawCapability);
        if (capability == null || capability.isExpired(Instant.now()) || !capability.scopeKey().equals(scopeKey)) {
            throw new BusinessException(ErrorCode.WIKI_CAPABILITY_EXPIRED);
        }
        return capability;
    }

    public void revoke(String rawCapability) {
        capabilities.remove(rawCapability);
    }
}
