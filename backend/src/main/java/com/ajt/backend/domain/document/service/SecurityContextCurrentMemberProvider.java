package com.ajt.backend.domain.document.service;

import com.ajt.backend.domain.member.Role;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextCurrentMemberProvider implements CurrentMemberProvider {

    @Override
    public CurrentMember currentMember() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedMember principal)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return new CurrentMember(principal.memberId(), toCurrentMemberRole(principal.role()));
    }

    private CurrentMemberRole toCurrentMemberRole(Role role) {
        return switch (role) {
            case ADMIN -> CurrentMemberRole.ADMIN;
            case EMPLOYEE -> CurrentMemberRole.EMPLOYEE;
        };
    }
}
