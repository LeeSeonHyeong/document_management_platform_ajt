package com.ajt.backend.domain.auth.dto;

/**
 * CSRF 발급, 로그아웃처럼 단순 처리 결과를 알려주는 응답입니다.
 */
public record AuthMessageResponse(
        String message
) {
}
