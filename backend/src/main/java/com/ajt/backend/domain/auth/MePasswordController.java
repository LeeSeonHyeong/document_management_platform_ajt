package com.ajt.backend.domain.auth;

import com.ajt.backend.domain.auth.dto.ChangePasswordRequest;
import com.ajt.backend.global.auth.AuthenticatedMember;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-USR-013 로그인 상태 비밀번호 변경 API입니다(S15P11B106-98).
 *
 * <p>`/me` 리소스의 자격증명 변경이라 인증 도메인에 두어 관리자 사용자 관리(MemberController)와 섞지 않는다.
 * 대상 사용자 ID를 받지 않고 인증 주체(@AuthenticationPrincipal)의 계정에만 적용하므로 타인 비밀번호 변경이
 * 구조적으로 불가능하다. 상태 변경(PATCH)이라 CSRF 보호와 인증 쿠키가 필요하다.
 */
@RestController
public class MePasswordController {

    private final AuthService authService;

    public MePasswordController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * PATCH /api/v1/me/password
     * 현재 비밀번호 확인 후 로그인한 본인의 비밀번호를 변경합니다. 성공 시 본문 없이 204를 반환합니다.
     */
    @PatchMapping("/api/v1/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeMyPassword(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        authService.changeMyPassword(loginMember, request);
    }
}
