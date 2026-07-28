package com.ajt.backend.global.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/**
 * CSRF 토큰 쿠키를 발급하는 서비스입니다.
 * 이 토큰은 로그인 정보가 아니라, 쿠키 기반 로그인에서 위조 요청을 줄이기 위한 확인값입니다.
 */
@Service
public class CsrfTokenService {

    public static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    public static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

    private static final Duration CSRF_MAX_AGE = Duration.ofHours(2);

    private final SecureRandom secureRandom = new SecureRandom();
    private final AuthTokenProperties properties;

    public CsrfTokenService(AuthTokenProperties properties) {
        this.properties = properties;
    }

    public String createToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public ResponseCookie createCookie(String token) {
        return ResponseCookie.from(CSRF_COOKIE_NAME, token)
                .httpOnly(false)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(CSRF_MAX_AGE)
                .build();
    }
}
