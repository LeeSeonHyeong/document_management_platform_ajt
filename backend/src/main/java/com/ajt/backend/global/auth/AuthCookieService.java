package com.ajt.backend.global.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/**
 * 인증 쿠키를 만들고 읽는 공통 서비스입니다.
 * 쿠키 옵션을 한곳에 모아 로그인과 로그아웃 응답이 같은 규칙으로 동작하게 합니다.
 */
@Service
public class AuthCookieService {

    public static final String ACCESS_TOKEN_COOKIE_NAME = "AJT_ACCESS_TOKEN";

    private final AuthTokenProperties properties;

    public AuthCookieService(AuthTokenProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie createAccessTokenCookie(String accessToken) {
        return baseAccessTokenCookie(accessToken)
                .maxAge(properties.accessTokenExpiration())
                .build();
    }

    public ResponseCookie expireAccessTokenCookie() {
        return baseAccessTokenCookie("")
                .maxAge(Duration.ZERO)
                .build();
    }

    public Optional<String> readAccessToken(HttpServletRequest request) {
        return readCookie(request, ACCESS_TOKEN_COOKIE_NAME);
    }

    private ResponseCookie.ResponseCookieBuilder baseAccessTokenCookie(String value) {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/");
    }

    static Optional<String> readCookie(HttpServletRequest request, String cookieName) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> cookieName.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }
}
