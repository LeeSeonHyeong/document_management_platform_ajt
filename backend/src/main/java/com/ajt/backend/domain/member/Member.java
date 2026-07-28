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

/**
 * 회원 계정 엔티티입니다.
 * 가입 승인 상태와 계정 활성 상태를 함께 보고 로그인 가능 여부를 판단합니다.
 */
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

    @Column(name = "password", nullable = false, length = 255)
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

    @Column(name = "updated_at")
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
        Member member = new Member(
                department,
                email,
                name,
                passwordHash,
                Role.EMPLOYEE,
                SignupStatus.APPROVED,
                AccountStatus.ACTIVE
        );
        member.employeeNo = employeeNo;
        return member;
    }

    /**
     * 거부된 가입 요청만 다시 pending 상태로 바꿀 수 있습니다.
     * pending/approved 중복 신청은 서비스에서 명확한 API 오류로 막습니다.
     */
    public void resubmitSignup(Department department, String email, String name, String passwordHash) {
        if (signupStatus != SignupStatus.REJECTED) {
            throw new IllegalStateException("거부된 가입 요청만 다시 신청할 수 있습니다.");
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
            throw new IllegalStateException("승인 대기 중인 가입 요청만 거부할 수 있습니다.");
        }
        this.signupStatus = SignupStatus.REJECTED;
        this.accountStatus = AccountStatus.INACTIVE;
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

    public Long getId() {
        return id;
    }

    public Department getDepartment() {
        return department;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getEmployeeNo() {
        return employeeNo;
    }

    public Role getRole() {
        return role;
    }

    public SignupStatus getSignupStatus() {
        return signupStatus;
    }

    public AccountStatus getAccountStatus() {
        return accountStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
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
}
