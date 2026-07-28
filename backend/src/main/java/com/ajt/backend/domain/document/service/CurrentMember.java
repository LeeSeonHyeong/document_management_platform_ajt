package com.ajt.backend.domain.document.service;

public record CurrentMember(long memberId, CurrentMemberRole role) {

    public boolean isAdmin() {
        return role == CurrentMemberRole.ADMIN;
    }
}
