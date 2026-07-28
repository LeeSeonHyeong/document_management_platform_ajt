package com.ajt.backend.domain.schedule.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "schedule_department")
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
