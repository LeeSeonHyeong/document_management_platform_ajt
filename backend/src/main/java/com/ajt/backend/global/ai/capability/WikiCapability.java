package com.ajt.backend.global.ai.capability;

import java.time.Instant;

public record WikiCapability(String scopeKey, long scopeVersion, Instant expiresAt) {

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
