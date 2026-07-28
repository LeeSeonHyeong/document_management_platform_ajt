package com.ajt.backend.global.auth;

import com.ajt.backend.domain.member.Member;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * AUTH-05~06 비밀번호 재설정 토큰을 처리합니다.
 * 요구사항에 따라 토큰은 DB에 저장하지 않고, 현재 비밀번호 해시와 만료 시각을 서명해 검증합니다.
 */
@Service
public class PasswordResetTokenService extends SignedTokenSupport {

    private final AuthTokenProperties properties;
    private final Clock clock;

    public PasswordResetTokenService(AuthTokenProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String createToken(Member member) {
        long expiresAt = clock.instant()
                .plus(properties.passwordResetExpiration())
                .getEpochSecond();
        String payload = String.join(
                "\n",
                "password-reset",
                member.getEmail(),
                String.valueOf(expiresAt),
                fingerprint(member.getPasswordHash())
        );
        return signPayload(payload, properties.passwordResetSecret());
    }

    public PasswordResetTokenData parse(String token) {
        String payload = verifyAndReadPayload(token, properties.passwordResetSecret());
        String[] values = payload.split("\n", -1);
        if (values.length != 4 || !"password-reset".equals(values[0])) {
            throw invalidTokenException();
        }
        if (isExpired(values[2])) {
            throw invalidTokenException();
        }
        return new PasswordResetTokenData(values[1], values[3]);
    }

    public boolean matchesCurrentPassword(Member member, PasswordResetTokenData tokenData) {
        return fingerprint(member.getPasswordHash()).equals(tokenData.passwordFingerprint());
    }

    @Override
    RuntimeException invalidTokenException() {
        return new BusinessException(ErrorCode.INVALID_OR_EXPIRED_RESET_TOKEN);
    }

    private boolean isExpired(String epochSecondText) {
        try {
            long expiresAt = Long.parseLong(epochSecondText);
            return clock.instant().getEpochSecond() > expiresAt;
        } catch (NumberFormatException exception) {
            return true;
        }
    }
}
