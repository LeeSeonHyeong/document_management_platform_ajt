package com.ajt.backend.domain.department;

import com.ajt.backend.domain.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.Getter;

/**
 * 회원, 문서 공개 범위, 일정 공개 범위에서 공통으로 사용하는 부서 엔티티입니다.
 * 부서 관리자는 부서 관리 API에서만 지정하거나 해제합니다.
 */
@Getter
@Entity
@Table(name = "department")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "department_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Member manager;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    protected Department() {
    }

    public Department(String name) {
        this.name = requireName(name);
    }

    /**
     * DEPT-02/03 부서명 규칙입니다.
     * 공백 이름은 저장하지 않고, 앞뒤 공백은 잘라서 저장합니다.
     */
    public void changeName(String name) {
        this.name = requireName(name);
    }

    /**
     * DEPT-02/03 부서 관리자 지정 규칙입니다.
     * 지정 가능한 회원인지는 서비스에서 확인하고, 엔티티는 연결만 맡습니다.
     */
    public void assignManager(Member manager) {
        this.manager = Objects.requireNonNull(manager, "부서 관리자는 null일 수 없습니다.");
    }

    /**
     * DEPT-03 부서 관리자 해제 규칙입니다.
     * 관리자 미지정 부서가 허용되므로 null로 비웁니다.
     */
    public void clearManager() {
        this.manager = null;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("부서명은 필수입니다.");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > 50) {
            throw new IllegalArgumentException("부서명은 50자 이하여야 합니다.");
        }
        return trimmedName;
    }
}
