package com.ajt.backend.domain.schedule.repository;

import com.ajt.backend.domain.schedule.model.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ScheduleRepository extends JpaRepository<Schedule, Long>, JpaSpecificationExecutor<Schedule> {

    long countBySourceGroupKey(String sourceGroupKey);

    /**
     * 부서를 공개 대상으로 삼은 일정이 있는지 확인합니다.
     * schedule_department의 idx_schedule_department_department 인덱스를 사용합니다.
     */
    boolean existsByDepartments_DepartmentId(long departmentId);
}
