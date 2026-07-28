package com.ajt.backend.domain.schedule.repository;

import com.ajt.backend.domain.schedule.model.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ScheduleRepository extends JpaRepository<Schedule, Long>, JpaSpecificationExecutor<Schedule> {

    long countBySourceGroupKey(String sourceGroupKey);
}
