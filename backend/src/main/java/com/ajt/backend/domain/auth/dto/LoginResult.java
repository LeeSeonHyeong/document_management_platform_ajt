package com.ajt.backend.domain.auth.dto;

/**
 * 서비스 내부 로그인 결과입니다.
 * 컨트롤러가 accessToken은 쿠키로, 나머지 정보는 응답 본문으로 나누어 내려줍니다.
 */
public record LoginResult(
        String accessToken,
        long expiresIn,
        AuthUserResponse user
) {
    public LoginResponse toResponse() {
        return new LoginResponse(expiresIn, user);
    }
}
