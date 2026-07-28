package com.ajt.backend.domain.auth;

import com.ajt.backend.domain.auth.dto.AuthUserResponse;
import com.ajt.backend.domain.auth.dto.LoginRequest;
import com.ajt.backend.domain.auth.dto.LoginResult;
import com.ajt.backend.domain.auth.dto.PasswordResetConfirmRequest;
import com.ajt.backend.domain.auth.dto.PasswordResetRequest;
import com.ajt.backend.domain.auth.dto.PasswordResetRequestResponse;
import com.ajt.backend.domain.auth.dto.SignupRequest;
import com.ajt.backend.domain.auth.dto.SignupResponse;
import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.AccountStatus;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.SignupStatus;
import com.ajt.backend.global.auth.AccessTokenService;
import com.ajt.backend.global.auth.PasswordResetTokenData;
import com.ajt.backend.global.auth.PasswordResetTokenService;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
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
    private final PasswordResetTokenService passwordResetTokenService;

    public AuthService(
            MemberRepository memberRepository,
            DepartmentRepository departmentRepository,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService,
            PasswordResetTokenService passwordResetTokenService
    ) {
        this.memberRepository = memberRepository;
        this.departmentRepository = departmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
        this.passwordResetTokenService = passwordResetTokenService;
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
     * 계정 존재 여부 노출을 막기 위해 이메일 존재 여부와 관계없이 같은 메시지를 반환합니다.
     */
    @Transactional(readOnly = true)
    public PasswordResetRequestResponse requestPasswordReset(PasswordResetRequest request) {
        memberRepository.findByEmail(Member.normalizeEmail(request.email()))
                .ifPresent(passwordResetTokenService::createToken);
        return new PasswordResetRequestResponse(PASSWORD_RESET_REQUEST_MESSAGE);
    }

    /**
     * AUTH-06 비밀번호 재설정입니다.
     * 토큰은 현재 비밀번호 해시와 연결되어 있어 비밀번호가 바뀌면 기존 토큰은 다시 쓸 수 없습니다.
     */
    @Transactional
    public void resetPassword(PasswordResetConfirmRequest request) {
        PasswordResetTokenData tokenData = passwordResetTokenService.parse(request.token());
        Member member = memberRepository.findByEmail(Member.normalizeEmail(tokenData.email()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_OR_EXPIRED_RESET_TOKEN));

        if (!passwordResetTokenService.matchesCurrentPassword(member, tokenData)) {
            throw new BusinessException(ErrorCode.INVALID_OR_EXPIRED_RESET_TOKEN);
        }

        member.changePassword(passwordEncoder.encode(request.newPassword()));
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
}
