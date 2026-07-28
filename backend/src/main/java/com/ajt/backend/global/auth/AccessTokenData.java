package com.ajt.backend.global.auth;

import com.ajt.backend.domain.member.Role;

/**
 * accessToken 안에 들어 있는 로그인 사용자 정보입니다.
 * 요청마다 쿠키 토큰을 검증한 뒤 현재 사용자 권한을 만들 때 사용합니다.
 */
public record AccessTokenData(
        Long memberId,
        String email,
        Role role,
        long expiresAt
) {
}
