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

    @Override
    RuntimeException invalidTokenException() {
        return new IllegalArgumentException("접근 토큰이 올바르지 않습니다.");
    }
}
