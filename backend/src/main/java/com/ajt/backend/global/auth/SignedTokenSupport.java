package com.ajt.backend.global.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

abstract class SignedTokenSupport {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    String signPayload(String payload, String secret) {
        String encodedPayload = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = hmac(encodedPayload, secret);
        return encodedPayload + "." + signature;
    }

    String verifyAndReadPayload(String token, String secret) {
        if (token == null || token.isBlank()) {
            throw invalidTokenException();
        }

        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            throw invalidTokenException();
        }

        String expectedSignature = hmac(parts[0], secret);
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8)
        )) {
            throw invalidTokenException();
        }

        return new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8);
    }

    String fingerprint(String value) {
        return hmac(value, "ajt-password-fingerprint");
    }

    abstract RuntimeException invalidTokenException();

    private String hmac(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("토큰 서명 처리 중 오류가 발생했습니다.", exception);
        }
    }
}
