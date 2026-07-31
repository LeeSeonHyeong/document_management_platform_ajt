package com.ajt.backend.domain.schedule.repository;

import com.ajt.backend.domain.schedule.model.Schedule;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    /**
     * 추가(S15P11B106-169): AI 에이전트용 일정 목록 조회입니다. 기간과 상태로 DB에서 좁히고
     * (idx_schedule_period_status) 공개 범위·키워드는 애플리케이션에서 거른다 — 부서 목록은
     * BatchSize로 모아 읽는다.
     *
     * <p>기간 겹침 판정은 공개 API 목록과 같다: 시작이 창 끝보다 앞이고 종료가 창 시작 이후.
     * 시작 시각이 가까운 순서로 정렬해 돌려주므로 상한을 넘길 때 앞에서부터 남기면 된다.
     */
    @Query("""
            select s from Schedule s
            where s.status = com.ajt.backend.domain.schedule.model.ScheduleStatus.APPROVED
              and s.startAt < :windowEndExclusive
              and s.endAt >= :windowStart
            order by s.startAt asc, s.id asc
            """)
    List<Schedule> findApprovedByPeriod(
            @Param("windowStart") Instant windowStart,
            @Param("windowEndExclusive") Instant windowEndExclusive
    );

    /**
     * 추가(S15P11B106-171): 공개 부서까지 함께 읽습니다. <b>트랜잭션 밖에서 공개 범위를 판정할 때</b>
     * 씁니다.
     *
     * <p>{@code findAllById} 는 자기 짧은 세션에서 읽고 바로 닫으므로 돌아온 엔티티가 detached 다.
     * 그 상태에서 {@code Schedule.departments}(lazy)를 건드리면 채울 세션이 없어
     * {@code LazyInitializationException} 이 난다 — 챗봇 답변 저장 직전에 그것으로 사용자에게 500이
     * 나갔다. 여기서 함께 읽어 두면 트랜잭션 범위를 늘리지 않고 끝난다.
     *
     * <p>{@code left join} 이어야 한다 — 부서가 없는 전사·개인 일정도 돌아와야 한다.
     */
    @Query("select distinct s from Schedule s left join fetch s.departments where s.id in :ids")
    List<Schedule> findAllByIdWithDepartments(@Param("ids") Collection<Long> ids);
}
