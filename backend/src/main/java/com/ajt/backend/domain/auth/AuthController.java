package com.ajt.backend.domain.auth;

import com.ajt.backend.domain.auth.dto.LoginRequest;
import com.ajt.backend.domain.auth.dto.LoginResponse;
import com.ajt.backend.domain.auth.dto.PasswordResetConfirmRequest;
import com.ajt.backend.domain.auth.dto.PasswordResetRequest;
import com.ajt.backend.domain.auth.dto.PasswordResetRequestResponse;
import com.ajt.backend.domain.auth.dto.SignupRequest;
import com.ajt.backend.domain.auth.dto.SignupResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * AUTH-02 로그인 API입니다.
     * 승인 완료 및 활성 상태인 회원에게 Bearer 접근 토큰을 발급합니다.
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * AUTH-01 회원가입 API입니다.
     * 신규 가입 요청은 관리자 승인 전까지 pending/inactive 상태로 저장됩니다.
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    /**
     * AUTH-05 비밀번호 재설정 메일 요청 API입니다.
     * 계정 존재 여부를 숨기기 위해 항상 같은 성공 메시지를 반환합니다.
     */
    @PostMapping("/password-reset-requests")
    public PasswordResetRequestResponse requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request
    ) {
        return authService.requestPasswordReset(request);
    }

    /**
     * AUTH-06 비밀번호 재설정 API입니다.
     * 유효한 재설정 토큰으로만 새 비밀번호를 저장합니다.
     */
    @PostMapping("/password-resets")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody PasswordResetConfirmRequest request) {
        authService.resetPassword(request);
    }
}
