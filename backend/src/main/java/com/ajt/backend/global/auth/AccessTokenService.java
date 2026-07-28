package com.ajt.backend.global.auth;

import com.ajt.backend.domain.member.Member;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * AUTH-02 로그인 성공 시 내려줄 접근 토큰을 만듭니다.
 * 현재는 초기 인증 API용 서명 토큰이며, JWT/RBAC Jira에서 표준 JWT 검증 필터와 함께 재검토합니다.
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

    public long expiresInSeconds() {
        return properties.accessTokenExpiration().toSeconds();
    }

    @Override
    RuntimeException invalidTokenException() {
        return new IllegalArgumentException("접근 토큰이 올바르지 않습니다.");
    }
}
