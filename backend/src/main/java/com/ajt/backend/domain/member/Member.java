package com.ajt.backend.domain.member;

import com.ajt.backend.domain.department.Department;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import lombok.Getter;

/**
 * 회원 계정 엔티티입니다.
 * 가입 승인 상태와 계정 활성 상태를 함께 보고 로그인 가능 여부와 관리자 처리 결과를 판단합니다.
 */
@Getter
@Entity
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "employee_no", unique = true, length = 20)
    private String employeeNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "signup_status", nullable = false, length = 30)
    private SignupStatus signupStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 30)
    private AccountStatus accountStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // 수정(S15P11B106-78): ERD의 member.updated_at은 NOT NULL이다. 엔티티에도 nullable=false를 명시해 ERD와 맞춘다.
    //   생성 시 @PrePersist가 createdAt과 같은 값으로 채우고, 이후 변경 시 @PreUpdate/touch가 최신 시각으로 갱신한다.
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Member() {
    }

    private Member(
            Department department,
            String email,
            String name,
            String passwordHash,
            Role role,
            SignupStatus signupStatus,
            AccountStatus accountStatus
    ) {
        this.department = Objects.requireNonNull(department, "부서는 필수입니다.");
        this.email = normalizeEmail(email);
        this.name = requireName(name);
        this.passwordHash = requirePasswordHash(passwordHash);
        this.role = Objects.requireNonNull(role, "역할은 필수입니다.");
        this.signupStatus = Objects.requireNonNull(signupStatus, "가입 상태는 필수입니다.");
        this.accountStatus = Objects.requireNonNull(accountStatus, "계정 상태는 필수입니다.");
    }

    /**
     * AUTH-01 회원가입 규칙입니다.
     * 신규 회원은 사번 없이 employee/pending/inactive 상태로 저장됩니다.
     */
    public static Member signup(Department department, String email, String name, String passwordHash) {
        return new Member(
                department,
                email,
                name,
                passwordHash,
                Role.EMPLOYEE,
                SignupStatus.PENDING,
                AccountStatus.INACTIVE
        );
    }

    public static Member approvedEmployee(
            Department department,
            String email,
            String name,
            String passwordHash,
            String employeeNo
    ) {
        return approved(department, email, name, passwordHash, employeeNo, Role.EMPLOYEE);
    }

    public static Member approved(
            Department department,
            String email,
            String name,
            String passwordHash,
            String employeeNo,
            Role role
    ) {
        Member member = new Member(
                department,
                email,
                name,
                passwordHash,
                role,
                SignupStatus.APPROVED,
                AccountStatus.ACTIVE
        );
        member.employeeNo = member.requireEmployeeNo(employeeNo);
        return member;
    }

    /**
     * 거절된 가입 신청만 다시 pending 상태로 바꿀 수 있습니다.
     * pending/approved 중복 신청은 서비스에서 명확한 API 오류로 막습니다.
     */
    public void resubmitSignup(Department department, String email, String name, String passwordHash) {
        if (signupStatus != SignupStatus.REJECTED) {
            throw new IllegalStateException("거절된 가입 신청만 다시 신청할 수 있습니다.");
        }
        this.department = Objects.requireNonNull(department, "부서는 필수입니다.");
        this.email = normalizeEmail(email);
        this.name = requireName(name);
        this.passwordHash = requirePasswordHash(passwordHash);
        this.role = Role.EMPLOYEE;
        this.signupStatus = SignupStatus.PENDING;
        this.accountStatus = AccountStatus.INACTIVE;
        this.employeeNo = null;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = requirePasswordHash(passwordHash);
    }

    public void rejectSignup() {
        if (signupStatus != SignupStatus.PENDING) {
            throw new IllegalStateException("승인 대기 상태의 가입 신청만 거절할 수 있습니다.");
        }
        this.signupStatus = SignupStatus.REJECTED;
        this.accountStatus = AccountStatus.INACTIVE;
        touch();
    }

    public void approveSignup(String employeeNo) {
        if (signupStatus != SignupStatus.PENDING) {
            throw new IllegalStateException("승인 대기 상태의 가입 신청만 승인할 수 있습니다.");
        }
        this.employeeNo = requireEmployeeNo(employeeNo);
        this.signupStatus = SignupStatus.APPROVED;
        this.accountStatus = AccountStatus.ACTIVE;
        touch();
    }

    /**
     * MEM-02 관리자 사용자 수정 규칙입니다.
     * null로 들어온 값은 변경하지 않고, 전달된 값만 현재 회원 정보에 반영합니다.
     *
     * <p>수정(S15P11B106-69): 부서장 자동 해제 규칙을 이 메서드 안으로 옮겼다. 역할·계정 상태 변경으로
     * 부서장 자격(APPROVED·ACTIVE·ADMIN)을 잃으면 인자로 받은 담당 부서(managedDepartment)의 부서장
     * 지정을 해제한다(FR-USR-008, 요구사항 v2.12). 어떤 경로로 강등·비활성해도 이 메서드를 거치면
     * 자격을 잃은 부서장 지정이 남지 않는다. 담당 부서 조회는 저장소 계층 책임이므로 서비스가 조회해
     * 넘겨준다(엔티티는 저장소에 직접 접근하지 않는다). 담당 부서가 없으면 null을 넘긴다.
     */
    public void updateByAdmin(
            Department department,
            String name,
            Role role,
            AccountStatus accountStatus,
            Department managedDepartment
    ) {
        if (department != null) {
            this.department = department;
        }
        if (name != null) {
            this.name = requireName(name);
        }
        if (role != null) {
            this.role = role;
        }
        if (accountStatus != null) {
            this.accountStatus = accountStatus;
        }
        // 자기-부서 가드: 넘겨받은 부서의 부서장이 실제로 이 회원일 때만 해제한다.
        //   (엉뚱한 부서가 전달돼도 남의 부서장 지정을 건드리지 않도록 방어.)
        //   참조 비교(==)는 JPA 프록시 때문에 어긋날 수 있어 ID로 비교한다.
        if (managedDepartment != null
                && managedDepartment.getManager() != null
                && Objects.equals(managedDepartment.getManager().getId(), this.id)
                && !isEligibleAsDepartmentManager()) {
            managedDepartment.clearManager();
        }
        touch();
    }

    /**
     * FR-USR-008 부서장 지정 자격입니다.
     * 부서장은 가입 승인(APPROVED)·계정 활성(ACTIVE) 상태의 관리자(ADMIN)만 맡을 수 있습니다.
     */
    public boolean isEligibleAsDepartmentManager() {
        return role == Role.ADMIN
                && signupStatus == SignupStatus.APPROVED
                && accountStatus == AccountStatus.ACTIVE;
    }

    /**
     * 로그인·인증이 허용되는 계정 상태인지 여부입니다(가입 승인 + 계정 활성).
     * 로그인 시점 검증과, 발급된 토큰의 매 요청 재검증(S15P11B106-198)이 같은 기준을 공유합니다.
     */
    public boolean isLoginAllowed() {
        return signupStatus == SignupStatus.APPROVED
                && accountStatus == AccountStatus.ACTIVE;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("이메일은 필수 입력 항목입니다.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("이름은 필수 입력 항목입니다.");
        }
        return name.trim();
    }

    private String requirePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("비밀번호 해시는 필수입니다.");
        }
        return passwordHash;
    }

    private String requireEmployeeNo(String employeeNo) {
        if (employeeNo == null || employeeNo.isBlank()) {
            throw new IllegalArgumentException("사번은 필수입니다.");
        }
        return employeeNo.trim();
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
