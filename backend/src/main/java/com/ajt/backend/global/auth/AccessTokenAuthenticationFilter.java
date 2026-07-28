package com.ajt.backend.global.auth;

import com.ajt.backend.global.error.ErrorCode;
import com.ajt.backend.global.error.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 쿠키의 AJT_ACCESS_TOKEN을 확인해 현재 로그인 사용자를 Spring Security에 등록합니다.
 * 프론트는 토큰을 직접 저장하지 않고, 브라우저가 쿠키를 자동으로 보내는 방식으로 인증합니다.
 */
@Component
public class AccessTokenAuthenticationFilter extends OncePerRequestFilter {

    private final AccessTokenService accessTokenService;
    private final AuthCookieService authCookieService;
    private final SecurityErrorResponseWriter responseWriter;

    public AccessTokenAuthenticationFilter(
            AccessTokenService accessTokenService,
            AuthCookieService authCookieService,
            SecurityErrorResponseWriter responseWriter
    ) {
        this.accessTokenService = accessTokenService;
        this.authCookieService = authCookieService;
        this.responseWriter = responseWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var accessToken = authCookieService.readAccessToken(request);
        if (accessToken.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AccessTokenData tokenData = accessTokenService.parse(accessToken.get());
            AuthenticatedMember principal = new AuthenticatedMember(
                    tokenData.memberId(),
                    tokenData.email(),
                    tokenData.role()
            );
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + tokenData.role().name()))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (RuntimeException exception) {
            SecurityContextHolder.clearContext();
            responseWriter.write(response, ErrorCode.INVALID_ACCESS_TOKEN, request.getRequestURI());
        }
    }
}
