package com.ajt.backend.domain.schedule.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 일정의 공개 부서입니다.
 * erd.sql의 uk_schedule_department를 매핑에도 선언해, 테스트 스키마가 운영 스키마와
 * 같은 제약을 갖도록 한다. 같은 부서로 수정할 때 삭제·삽입 순서 문제를 테스트에서 잡기 위함이다.
 */
@Entity
@Table(
        name = "schedule_department",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_schedule_department",
                columnNames = {"schedule_id", "department_id"}
        )
)
public class ScheduleDepartment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_department_id")
    private Long id;

    @Column(name = "department_id", nullable = false)
    private long departmentId;

    protected ScheduleDepartment() {
    }

    ScheduleDepartment(long departmentId) {
        this.departmentId = departmentId;
    }

    public Long id() {
        return id;
    }

    public long departmentId() {
        return departmentId;
    }
}
