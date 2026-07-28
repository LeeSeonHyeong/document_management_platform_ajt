package com.ajt.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ajt.backend.domain.member.Role;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("SecurityContext 현재 사용자 provider")
class SecurityContextCurrentMemberProviderTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("AuthenticatedMember principal을 문서 도메인 현재 사용자로 변환한다")
    void returnsCurrentMemberFromAuthenticatedPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedMember(10L, "admin@ajt.com", Role.ADMIN),
                null
        ));

        CurrentMember currentMember = new SecurityContextCurrentMemberProvider().currentMember();

        assertThat(currentMember.memberId()).isEqualTo(10L);
        assertThat(currentMember.role()).isEqualTo(CurrentMemberRole.ADMIN);
        assertThat(currentMember.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("인증 정보가 없으면 UNAUTHORIZED를 반환한다")
    void rejectsMissingAuthentication() {
        assertThatThrownBy(() -> new SecurityContextCurrentMemberProvider().currentMember())
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
