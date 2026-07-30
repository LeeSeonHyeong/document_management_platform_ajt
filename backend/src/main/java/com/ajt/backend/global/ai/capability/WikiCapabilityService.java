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

    public WikiCapability require(String rawCapability, String scopeKey) {
        if (rawCapability == null || rawCapability.isBlank() || scopeKey == null || scopeKey.isBlank()) {
            throw new BusinessException(ErrorCode.WIKI_CAPABILITY_EXPIRED);
        }
        WikiCapability capability = capabilities.remove(rawCapability);
        if (capability == null || capability.isExpired(Instant.now()) || !capability.scopeKey().equals(scopeKey)) {
            throw new BusinessException(ErrorCode.WIKI_CAPABILITY_EXPIRED);
        }
        capabilities.put(rawCapability, capability);
        return capability;
    }

    public void revoke(String rawCapability) {
        capabilities.remove(rawCapability);
    }
}
