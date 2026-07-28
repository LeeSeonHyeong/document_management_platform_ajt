package com.ajt.backend.domain.auth.dto;

/**
 * 로그인 성공 후 응답 본문으로 내려주는 정보입니다.
 * accessToken은 HttpOnly 쿠키로만 내려가므로 JSON 본문에는 포함하지 않습니다.
 */
public record LoginResponse(
        long expiresIn,
        AuthUserResponse user
) {
}
