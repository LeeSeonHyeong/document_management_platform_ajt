package com.ajt.backend.domain.schedule.repository;

import com.ajt.backend.domain.schedule.model.Schedule;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleRepository extends JpaRepository<Schedule, Long>, JpaSpecificationExecutor<Schedule> {

    long countBySourceGroupKey(String sourceGroupKey);

    /**
     * 부서를 공개 대상으로 삼은 일정이 있는지 확인합니다.
     * schedule_department의 idx_schedule_department_department 인덱스를 사용합니다.
     */
    boolean existsByDepartments_DepartmentId(long departmentId);

    /**
     * 수정(S15P11B106-87): 일정 수정 시 동시 저장을 직렬화하려고 행에 짧은 쓰기 락(FOR UPDATE)을 건다.
     * 락은 수정 트랜잭션 동안만 유지되며(화면 진입부터 잡지 않음), 락 획득 후 최신 updated_at을 읽어
     * 요청의 expectedUpdatedAt과 비교해 낙관적 충돌을 판정한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Schedule s where s.id = :id")
    Optional<Schedule> findByIdForUpdate(@Param("id") long id);
}
