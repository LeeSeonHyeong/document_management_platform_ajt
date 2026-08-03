package com.ajt.backend.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.global.error.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 활동 중인 세션이 끊기지 않는지 확인합니다(S15P11B106-206).
 *
 * <p>FR-USR-005는 <b>활동이 없는</b> 세션을 만료하라고 요구하지만, accessToken이 발급 시점 기준
 * 고정 수명이라 계속 쓰고 있어도 1시간이면 끊겼다. 갱신 수단이 아예 없었다.
 *
 * <p>시각을 움직여야 하므로 {@link Clock}을 고정해 만든다.
 */
@DisplayName("세션 슬라이딩 갱신")
class AccessTokenSlidingRenewalTest {

    private static final Duration TTL = Duration.ofHours(1);
    private static final Instant LOGIN_AT = Instant.parse("2026-08-03T00:00:00Z");

    private final AuthTokenProperties properties = new AuthTokenProperties(
            "test-access-secret", TTL, "test-reset-secret", Duration.ofMinutes(30), false);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final SecurityErrorResponseWriter responseWriter = mock(SecurityErrorResponseWriter.class);

    @Test
    @DisplayName("남은 수명이 절반 아래면 갱신 대상이다")
    void renewsAfterHalfLife() throws Exception {
        String token = serviceAt(LOGIN_AT).createAccessToken(member());

        // 29분 뒤 — 남은 31분은 절반(30분)보다 많다.
        assertThat(atMinutes(29).shouldRenew(atMinutes(29).parse(token))).isFalse();
        // 31분 뒤 — 남은 29분은 절반 아래다.
        assertThat(atMinutes(31).shouldRenew(atMinutes(31).parse(token))).isTrue();
    }

    @Test
    @DisplayName("절반이 지난 요청은 쿠키를 새로 내려 세션을 이어 준다")
    void reissuesTheCookieWhenTheSessionIsHalfSpent() throws Exception {
        MockHttpServletResponse response = callFilterAt(atMinutes(45), atMinutes(0));

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains(AuthCookieService.ACCESS_TOKEN_COOKIE_NAME);
        // 새 쿠키의 수명은 다시 TTL 전체다 — 그래서 활동이 이어지는 동안 끊기지 않는다.
        assertThat(setCookie).contains("Max-Age=" + TTL.toSeconds());
    }

    @Test
    @DisplayName("아직 절반이 안 지났으면 쿠키를 다시 내리지 않는다")
    void doesNotTouchTheCookieEarlyInTheSession() throws Exception {
        MockHttpServletResponse response = callFilterAt(atMinutes(10), atMinutes(0));

        // 매 요청마다 Set-Cookie 를 남기지 않는다.
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("만료된 뒤에는 갱신하지 않고 인증을 거부한다")
    void doesNotResurrectAnExpiredSession() throws Exception {
        // 1시간 넘게 요청이 없었던 세션 — FR-USR-005 가 만료하라고 한 그 경우다.
        MockHttpServletResponse response = callFilterAt(atMinutes(61), atMinutes(0));

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /** 토큰은 {@code issuedAt} 시점에 만들고, 필터는 {@code requestAt} 시점에 돈다. */
    private MockHttpServletResponse callFilterAt(AccessTokenService atRequest, AccessTokenService atIssue)
            throws Exception {
        SecurityContextHolder.clearContext();
        Member member = member();
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        AuthCookieService cookieService = new AuthCookieService(properties);
        var filter = new AccessTokenAuthenticationFilter(
                atRequest, cookieService, responseWriter, memberRepository);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie(
                AuthCookieService.ACCESS_TOKEN_COOKIE_NAME, atIssue.createAccessToken(member)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));
        return response;
    }

    private AccessTokenService serviceAt(Instant instant) {
        return new AccessTokenService(properties, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private AccessTokenService atMinutes(long minutes) {
        return serviceAt(LOGIN_AT.plus(Duration.ofMinutes(minutes)));
    }

    private Member member() throws Exception {
        Member member = Member.approvedEmployee(
                new Department("개발팀"), "user@ajt.com", "홍길동", "hashed", "AJT-2026-0001");
        Field id = Member.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(member, 1L);
        return member;
    }
}
