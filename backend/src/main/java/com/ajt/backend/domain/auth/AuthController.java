package com.ajt.backend.domain.auth;

import com.ajt.backend.domain.auth.dto.AuthMessageResponse;
import com.ajt.backend.domain.auth.dto.LoginRequest;
import com.ajt.backend.domain.auth.dto.LoginResponse;
import com.ajt.backend.domain.auth.dto.LoginResult;
import com.ajt.backend.domain.auth.dto.PasswordResetConfirmRequest;
import com.ajt.backend.domain.auth.dto.PasswordResetRequest;
import com.ajt.backend.domain.auth.dto.PasswordResetRequestResponse;
import com.ajt.backend.domain.auth.dto.PasswordResetVerifyRequest;
import com.ajt.backend.domain.auth.dto.SignupRequest;
import com.ajt.backend.domain.auth.dto.SignupResponse;
import com.ajt.backend.global.auth.AuthCookieService;
import com.ajt.backend.global.auth.CsrfTokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieService authCookieService;
    private final CsrfTokenService csrfTokenService;

    public AuthController(
            AuthService authService,
            AuthCookieService authCookieService,
            CsrfTokenService csrfTokenService
    ) {
        this.authService = authService;
        this.authCookieService = authCookieService;
        this.csrfTokenService = csrfTokenService;
    }

    /**
     * AUTH-02 로그인 API입니다.
     * 성공하면 accessToken은 JSON이 아니라 AJT_ACCESS_TOKEN HttpOnly 쿠키로 내려줍니다.
     */
    @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response
    ) {
        LoginResult result = authService.login(request);
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                authCookieService.createAccessTokenCookie(result.accessToken()).toString()
        );
        return result.toResponse();
    }

    /**
     * AUTH-CSRF CSRF 토큰 발급 API입니다.
     * 프론트는 이 쿠키 값을 읽어서 상태 변경 요청의 X-XSRF-TOKEN 헤더로 보내면 됩니다.
     */
    @GetMapping("/csrf")
    public AuthMessageResponse csrf(HttpServletResponse response) {
        String csrfToken = csrfTokenService.createToken();
        response.addHeader(HttpHeaders.SET_COOKIE, csrfTokenService.createCookie(csrfToken).toString());
        return new AuthMessageResponse("CSRF 토큰이 발급되었습니다.");
    }

    /**
     * AUTH-04 로그아웃 API입니다.
     * 현재 인증 쿠키를 만료시켜 이후 요청에서 다시 로그인하도록 만듭니다.
     */
    @PostMapping("/logout")
    public AuthMessageResponse logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.expireAccessTokenCookie().toString());
        return new AuthMessageResponse("로그아웃되었습니다.");
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
            @Valid @RequestBody PasswordResetRequest request,
            HttpServletRequest httpRequest
    ) {
        return authService.requestPasswordReset(request, httpRequest.getRemoteAddr());
    }

    /**
     * AUTH-06a 인증번호 확인 API입니다. (신규)
     * 인증번호가 유효하면 프론트는 비밀번호 수정 화면으로 진행합니다.
     */
    @PostMapping("/password-reset-verify")
    public AuthMessageResponse verifyResetCode(@Valid @RequestBody PasswordResetVerifyRequest request) {
        return authService.verifyResetCode(request);
    }

    /**
     * AUTH-06 비밀번호 재설정 API입니다.
     * 수정: 유효한 인증번호(이메일 + 6자리)로만 새 비밀번호를 저장합니다.
     */
    @PostMapping("/password-resets")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody PasswordResetConfirmRequest request) {
        authService.resetPassword(request);
    }
}
