package com.ajt.backend.global.auth;

import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.global.error.BusinessException;
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
    private final MemberRepository memberRepository;

    public AccessTokenAuthenticationFilter(
            AccessTokenService accessTokenService,
            AuthCookieService authCookieService,
            SecurityErrorResponseWriter responseWriter,
            MemberRepository memberRepository
    ) {
        this.accessTokenService = accessTokenService;
        this.authCookieService = authCookieService;
        this.responseWriter = responseWriter;
        this.memberRepository = memberRepository;
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
            // 수정(S15P11B106-198): 발급된 토큰이라도 매 요청마다 최신 계정 상태를 DB에서 확인한다.
            //   로그인 후 관리자가 계정을 비활성화(INACTIVE)·미승인/거부(PENDING/REJECTED) 처리했거나 회원이
            //   삭제된 경우, 남아 있는 쿠키만으로는 접근하지 못하도록 인증 실패로 처리한다.
            Member member = memberRepository.findById(tokenData.memberId())
                    .filter(Member::isLoginAllowed)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN));
            // 수정(S15P11B106-198): 인증 객체와 권한(GrantedAuthority)을 토큰이 아니라 DB의 최신 회원 기준으로 만든다.
            //   로그인 후 관리자가 사원(EMPLOYEE)으로 강등되면, 기존 토큰에 admin이 남아 있어도 다음 요청부터
            //   DB의 현재 role(EMPLOYEE)로 처리돼 관리자 전용 API가 막힌다.
            AuthenticatedMember principal = new AuthenticatedMember(
                    member.getId(),
                    member.getEmail(),
                    member.getRole()
            );
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name()))
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
