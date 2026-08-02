package com.ajt.backend.domain.member;

import com.ajt.backend.global.config.SuperAdminProperties;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 최고관리자(super-admin) 판별을 한곳에서 관리하는 정책 컴포넌트입니다(S15P11B106-83, S15P11B106-146).
 *
 * <p>이 프로젝트는 별도 SUPER_ADMIN role을 두지 않는다. 부서관리자도 {@code role=ADMIN}이다.
 *
 * <p>수정(S15P11B106-146): 예전에는 "ADMIN + 어느 부서 manager도 아님"으로 최고관리자를 판별했다. 그러나 그러면
 * 최고관리자가 부서 관리 화면에서 어느 부서의 manager로 지정되는 순간 최고관리자 자격을 잃어(가입 승인 메뉴가
 * 사라져) 버린다. 그래서 최고관리자는 설정값({@code ajt.super-admin.email})으로 고정 식별한다.
 * <ul>
 *   <li>최고관리자 = {@code role=ADMIN} + {@code signupStatus=APPROVED} + {@code accountStatus=ACTIVE} + 이메일이 설정값과 일치</li>
 *   <li>부서관리자 = 그 외의 {@code role=ADMIN} (설정 이메일이 아닌 관리자)</li>
 *   <li>일반 사원 = {@code role=EMPLOYEE}</li>
 * </ul>
 *
 * <p>사용자 관리·가입 승인/거절 권한 검사(MemberService.requireSuperAdmin)와 로그인·내 정보 응답의
 * {@code isSuperAdmin} 필드가 <b>동일한 기준</b>을 쓰도록 판별을 여기로 단일화한다.
 */
@Component
public class SuperAdminChecker {

    private final String superAdminEmail;

    public SuperAdminChecker(SuperAdminProperties properties) {
        this.superAdminEmail = properties.email();
    }

    /**
     * 회원 엔티티 기준 최고관리자 판별입니다.
     * role=ADMIN, 가입 승인(APPROVED), 활성(ACTIVE), 설정 이메일 일치를 모두 만족해야 한다.
     */
    public boolean isSuperAdmin(Member member) {
        if (member == null) {
            return false;
        }
        return member.getRole() == Role.ADMIN
                && member.getSignupStatus() == SignupStatus.APPROVED
                && member.getAccountStatus() == AccountStatus.ACTIVE
                && matchesSuperAdminEmail(member.getEmail());
    }

    /**
     * 로그인 주체(인증된 회원) 기준 최고관리자 판별입니다.
     * 로그인은 APPROVED+ACTIVE 계정만 통과하므로(AuthService.canLogin) 여기서는 role=ADMIN과 이메일 일치만 확인한다.
     *
     * @param email   인증된 회원 이메일
     * @param isAdmin 대상이 {@code role=ADMIN}인지 여부
     */
    public boolean isSuperAdmin(String email, boolean isAdmin) {
        return isAdmin && matchesSuperAdminEmail(email);
    }

    /** 해당 이메일이 설정된 최고관리자 이메일인지 여부입니다(부서 관리자 지정 차단·후보 제외에 사용). */
    public boolean isConfiguredSuperAdminEmail(String email) {
        return matchesSuperAdminEmail(email);
    }

    /** 설정된 최고관리자 이메일입니다(부서 관리자 후보 목록에서 제외할 때 사용). */
    public String superAdminEmail() {
        return superAdminEmail;
    }

    private boolean matchesSuperAdminEmail(String email) {
        if (superAdminEmail == null || superAdminEmail.isBlank() || email == null) {
            return false;
        }
        return superAdminEmail.equalsIgnoreCase(email.trim());
    }
}
