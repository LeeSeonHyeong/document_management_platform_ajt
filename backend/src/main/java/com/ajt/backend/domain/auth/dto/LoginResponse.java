package com.ajt.backend.domain.auth.dto;

/**
 * 로그인 성공 시 내려주는 토큰과 로그인한 회원 정보입니다.
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        AuthUserResponse user
) {
}
