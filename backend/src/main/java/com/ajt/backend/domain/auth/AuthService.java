package com.ajt.backend.domain.auth;

import com.ajt.backend.domain.auth.dto.AuthMessageResponse;
import com.ajt.backend.domain.auth.dto.AuthUserResponse;
import com.ajt.backend.domain.auth.dto.LoginRequest;
import com.ajt.backend.domain.auth.dto.LoginResult;
import com.ajt.backend.domain.auth.dto.PasswordResetConfirmRequest;
import com.ajt.backend.domain.auth.dto.PasswordResetRequest;
import com.ajt.backend.domain.auth.dto.PasswordResetRequestResponse;
import com.ajt.backend.domain.auth.dto.PasswordResetVerifyRequest;
import com.ajt.backend.domain.auth.dto.SignupRequest;
import com.ajt.backend.domain.auth.dto.SignupResponse;
import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.AccountStatus;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.SignupStatus;
import com.ajt.backend.global.auth.AccessTokenService;
import com.ajt.backend.global.auth.PasswordResetCodeStore;
import com.ajt.backend.global.auth.PasswordResetRateLimiter;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import com.ajt.backend.global.mail.EmailSender;
import java.security.SecureRandom;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String PASSWORD_RESET_REQUEST_MESSAGE =
            "입력한 이메일이 등록되어 있다면 비밀번호 재설정 안내를 전송했습니다.";

    private final MemberRepository memberRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;
    // 수정: 링크 토큰(PasswordResetTokenService) → 인증번호 방식으로 교체. 코드 저장소 + 메일 발송기를 사용한다.
    private final PasswordResetCodeStore passwordResetCodeStore;
    private final PasswordResetRateLimiter passwordResetRateLimiter;
    private final EmailSender emailSender;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            MemberRepository memberRepository,
            DepartmentRepository departmentRepository,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService,
            PasswordResetCodeStore passwordResetCodeStore,
            PasswordResetRateLimiter passwordResetRateLimiter,
            EmailSender emailSender
    ) {
        this.memberRepository = memberRepository;
        this.departmentRepository = departmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
        this.passwordResetCodeStore = passwordResetCodeStore;
        this.passwordResetRateLimiter = passwordResetRateLimiter;
        this.emailSender = emailSender;
    }

    /**
     * AUTH-01 회원가입 요청입니다.
     * 신규 이메일은 pending/inactive로 저장하고, rejected 이메일은 같은 회원 행을 다시 pending으로 돌립니다.
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = Member.normalizeEmail(request.email());
        Department department = findDepartment(request.departmentId());
        String encodedPassword = passwordEncoder.encode(request.password());

        return memberRepository.findByEmail(email)
                .map(member -> resubmitOrThrow(member, department, email, request.name(), encodedPassword))
                .orElseGet(() -> createSignup(department, email, request.name(), encodedPassword));
    }

    /**
     * AUTH-02 로그인입니다.
     * 승인 완료이면서 활성 상태인 회원만 accessToken을 받을 수 있습니다.
     */
    @Transactional(readOnly = true)
    public LoginResult login(LoginRequest request) {
        String email = Member.normalizeEmail(request.email());
        Member member = memberRepository.findByEmail(email)
                .filter(found -> passwordEncoder.matches(request.password(), found.getPasswordHash()))
                .filter(this::canLogin)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        return new LoginResult(
                accessTokenService.createAccessToken(member),
                accessTokenService.expiresInSeconds(),
                AuthUserResponse.from(member)
        );
    }

    /**
     * AUTH-05 비밀번호 재설정 요청입니다.
     * 수정: 링크 토큰 대신 6자리 인증번호를 만들어 저장하고 이메일로 발송한다.
     * 계정 존재 여부를 숨기기 위해 등록 여부와 관계없이 같은 메시지를 반환한다.
     */
    @Transactional(readOnly = true)
    public PasswordResetRequestResponse requestPasswordReset(PasswordResetRequest request, String clientIp) {
        String email = Member.normalizeEmail(request.email());
        // 수정: 요청 rate limit(429). 이메일·IP 기준으로 과도한 요청을 차단한다(무차별·이메일 폭탄 방지).
        passwordResetRateLimiter.check("email:" + email);
        passwordResetRateLimiter.check("ip:" + clientIp);
        memberRepository.findByEmail(email).ifPresent(member -> {
            String code = generateCode();
            passwordResetCodeStore.save(email, code);
            emailSender.sendPasswordResetCode(member.getEmail(), code);
        });
        return new PasswordResetRequestResponse(PASSWORD_RESET_REQUEST_MESSAGE);
    }

    /**
     * AUTH-06a 인증번호 확인입니다. (신규)
     * 인증번호가 유효하면 프론트가 비밀번호 수정 화면으로 진행한다. 실제 변경은 resetPassword에서 다시 확인한다.
     */
    @Transactional(readOnly = true)
    public AuthMessageResponse verifyResetCode(PasswordResetVerifyRequest request) {
        String email = Member.normalizeEmail(request.email());
        if (!passwordResetCodeStore.matches(email, request.code())) {
            throw new BusinessException(ErrorCode.INVALID_OR_EXPIRED_RESET_CODE);
        }
        return new AuthMessageResponse("인증번호가 확인되었습니다.");
    }

    /**
     * AUTH-06 비밀번호 재설정입니다.
     * 수정: 인증번호가 유효하면 새 비밀번호로 바꾸고, 사용한 인증번호는 즉시 폐기한다.
     */
    @Transactional
    public void resetPassword(PasswordResetConfirmRequest request) {
        String email = Member.normalizeEmail(request.email());
        if (!passwordResetCodeStore.matches(email, request.code())) {
            throw new BusinessException(ErrorCode.INVALID_OR_EXPIRED_RESET_CODE);
        }
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_OR_EXPIRED_RESET_CODE));

        member.changePassword(passwordEncoder.encode(request.newPassword()));
        passwordResetCodeStore.remove(email);
    }

    private SignupResponse createSignup(
            Department department,
            String email,
            String name,
            String encodedPassword
    ) {
        try {
            Member member = memberRepository.save(Member.signup(department, email, name, encodedPassword));
            return SignupResponse.from(member);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.SIGNUP_ALREADY_PENDING);
        }
    }

    private SignupResponse resubmitOrThrow(
            Member member,
            Department department,
            String email,
            String name,
            String encodedPassword
    ) {
        if (member.getSignupStatus() == SignupStatus.PENDING) {
            throw new BusinessException(ErrorCode.SIGNUP_ALREADY_PENDING);
        }
        if (member.getSignupStatus() == SignupStatus.APPROVED) {
            throw new BusinessException(ErrorCode.SIGNUP_ALREADY_APPROVED);
        }

        member.resubmitSignup(department, email, name, encodedPassword);
        return SignupResponse.from(member);
    }

    private Department findDepartment(String departmentId) {
        Long id = parseDepartmentId(departmentId);
        return departmentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND));
    }

    private Long parseDepartmentId(String departmentId) {
        try {
            return Long.valueOf(departmentId);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "부서 ID는 숫자 문자열이어야 합니다.");
        }
    }

    private boolean canLogin(Member member) {
        return member.getSignupStatus() == SignupStatus.APPROVED
                && member.getAccountStatus() == AccountStatus.ACTIVE;
    }

    // 수정: 6자리 인증번호(000000~999999)를 생성한다.
    private String generateCode() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }
}
