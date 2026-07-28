package com.ajt.backend.global.auth;

import com.ajt.backend.domain.member.Role;

/**
 * Spring Security에 저장되는 현재 로그인 회원 정보입니다.
 * DB 전체 회원 객체가 아니라 요청 처리에 필요한 최소 정보만 보관합니다.
 */
public record AuthenticatedMember(
        Long memberId,
        String email,
        Role role
) {
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
