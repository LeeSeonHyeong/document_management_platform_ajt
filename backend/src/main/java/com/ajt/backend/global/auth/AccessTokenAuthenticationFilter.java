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

        // 수정: try에는 토큰 파싱·인증 등록까지만 둔다. 인증 실패는 여기서 401로 처리하고 return으로 종료한다.
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
        } catch (RuntimeException exception) {
            SecurityContextHolder.clearContext();
            responseWriter.write(response, ErrorCode.INVALID_ACCESS_TOKEN, request.getRequestURI());
            return;
        }

        // 수정: doFilter를 try 밖으로 이동. 인증 이후(컨트롤러 등)에서 난 예외가 토큰 오류(401)로
        //       오인되거나 응답이 이중 기록되는 문제를 막기 위함.
        filterChain.doFilter(request, response);
    }
}
