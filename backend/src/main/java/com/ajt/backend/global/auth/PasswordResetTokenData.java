package com.ajt.backend.global.auth;

public record PasswordResetTokenData(
        String email,
        String passwordFingerprint
) {
}
