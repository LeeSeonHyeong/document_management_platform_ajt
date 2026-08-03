package com.ajt.backend.global.auth;

import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.Role;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * 로그인 성공 후 사용할 accessToken을 만들고 검증합니다.
 * 최신 API 명세에서는 이 토큰을 JSON이 아니라 HttpOnly 쿠키로만 내려줍니다.
 */
@Service
public class AccessTokenService extends SignedTokenSupport {

    private final AuthTokenProperties properties;
    private final Clock clock;

    public AccessTokenService(AuthTokenProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String createAccessToken(Member member) {
        long expiresAt = clock.instant()
                .plus(properties.accessTokenExpiration())
                .getEpochSecond();
        String payload = String.join(
                "\n",
                "access",
                String.valueOf(member.getId()),
                member.getEmail(),
                member.getRole().apiValue(),
                String.valueOf(expiresAt)
        );
        return signPayload(payload, properties.accessTokenSecret());
    }

    public AccessTokenData parse(String token) {
        String payload = verifyAndReadPayload(token, properties.accessTokenSecret());
        String[] values = payload.split("\n", -1);
        if (values.length != 5 || !"access".equals(values[0])) {
            throw invalidTokenException();
        }

        try {
            long expiresAt = Long.parseLong(values[4]);
            if (clock.instant().getEpochSecond() > expiresAt) {
                throw invalidTokenException();
            }
            return new AccessTokenData(
                    Long.valueOf(values[1]),
                    values[2],
                    Role.fromApiValue(values[3]),
                    expiresAt
            );
        } catch (RuntimeException exception) {
            throw invalidTokenException();
        }
    }

    public long expiresInSeconds() {
        return properties.accessTokenExpiration().toSeconds();
    }

    /**
     * 남은 수명이 절반 아래로 내려갔는지 여부입니다(S15P11B106-206).
     *
     * <p>FR-USR-005는 <b>활동이 없는</b> 세션을 만료하라고 요구하지만, 이 토큰은 발급 시점 기준
     * 고정 수명이라 쓰고 있어도 끊겼다. 인증에 성공한 요청에서 이 값이 참이면 쿠키를 다시 내려
     * 세션을 이어 준다 — 활동이 있으면 유지되고, 만료 시간만큼 요청이 없으면 만료된다.
     *
     * <p>절반을 기준으로 두는 이유는 매 요청에 {@code Set-Cookie}를 남기지 않기 위해서다.
     * refreshToken을 만들지 않는 것은 FR-USR-002가 금지하기 때문이다 — 같은 accessToken 쿠키를
     * 다시 내려주는 것뿐이다.
     */
    public boolean shouldRenew(AccessTokenData tokenData) {
        long remaining = tokenData.expiresAt() - clock.instant().getEpochSecond();
        return remaining < properties.accessTokenExpiration().toSeconds() / 2;
    }

    @Override
    RuntimeException invalidTokenException() {
        return new IllegalArgumentException("접근 토큰이 올바르지 않습니다.");
    }
}