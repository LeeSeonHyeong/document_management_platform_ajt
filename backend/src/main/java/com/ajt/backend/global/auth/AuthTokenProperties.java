package com.ajt.backend.global.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ajt.auth")
public record AuthTokenProperties(
        String accessTokenSecret,
        Duration accessTokenExpiration,
        String passwordResetSecret,
        Duration passwordResetExpiration,
        Boolean cookieSecure
) {

    public AuthTokenProperties {
        if (accessTokenSecret == null || accessTokenSecret.isBlank()) {
            accessTokenSecret = "local-dev-access-token-secret";
        }
        if (accessTokenExpiration == null) {
            accessTokenExpiration = Duration.ofHours(1);
        }
        if (passwordResetSecret == null || passwordResetSecret.isBlank()) {
            passwordResetSecret = "local-dev-password-reset-secret";
        }
        if (passwordResetExpiration == null) {
            passwordResetExpiration = Duration.ofMinutes(30);
        }
        if (cookieSecure == null) {
            cookieSecure = true;
        }
    }
}
