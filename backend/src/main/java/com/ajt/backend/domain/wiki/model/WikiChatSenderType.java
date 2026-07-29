package com.ajt.backend.domain.wiki.model;

import java.util.Locale;

/**
 * Wiki 관리자 대화의 발신 주체입니다.
 * DB는 CHECK 제약으로 대문자 ADMIN·AGENT만 허용하고, API·FastAPI 계약은 소문자를 사용합니다.
 */
public enum WikiChatSenderType {
    ADMIN,
    AGENT;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
