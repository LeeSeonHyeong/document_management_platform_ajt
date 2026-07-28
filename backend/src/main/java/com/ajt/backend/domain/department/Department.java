package com.ajt.backend.domain.department;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 부서 엔티티입니다.
 * 회원가입 화면에서는 부서 ID와 이름만 공개하고, 관리자 지정은 부서 관리 기능에서 다시 검토합니다.
 */
@Entity
@Table(name = "department")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "department_id")
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    protected Department() {
    }

    public Department(String name) {
        this.name = requireName(name);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("부서명은 필수입니다.");
        }
        return name.trim();
    }
}
