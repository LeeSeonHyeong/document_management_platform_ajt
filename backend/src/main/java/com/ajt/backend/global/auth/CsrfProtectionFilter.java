package com.ajt.backend.global.auth;

import com.ajt.backend.global.error.ErrorCode;
import com.ajt.backend.global.error.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 쿠키 로그인 상태에서 POST/PATCH/PUT/DELETE 요청의 CSRF 토큰을 확인합니다.
 * 로그인, 회원가입처럼 공개된 인증 API는 명세에 맞춰 예외 처리합니다.
 */
@Component
public class CsrfProtectionFilter extends OncePerRequestFilter {

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final SecurityErrorResponseWriter responseWriter;

    public CsrfProtectionFilter(SecurityErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!shouldCheck(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String headerToken = request.getHeader(CsrfTokenService.CSRF_HEADER_NAME);
        String cookieToken = AuthCookieService.readCookie(request, CsrfTokenService.CSRF_COOKIE_NAME)
                .orElse(null);
        if (headerToken == null || cookieToken == null || !headerToken.equals(cookieToken)) {
            responseWriter.write(response, ErrorCode.CSRF_TOKEN_INVALID, request.getRequestURI());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean shouldCheck(HttpServletRequest request) {
        if (!UNSAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/h2-console/")) {
            return false;
        }
        if (AuthCookieService.readCookie(request, AuthCookieService.ACCESS_TOKEN_COOKIE_NAME).isEmpty()) {
            return false;
        }
        return !Set.of(
                "/api/v1/auth/login",
                "/api/v1/auth/signup",
                "/api/v1/auth/password-reset-requests",
                "/api/v1/auth/password-resets"
        ).contains(path);
    }
}
