package com.ajt.backend.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleStatus;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
class ScheduleRepositoryTest {

    private static final Instant START = Instant.parse("2026-08-03T01:00:00Z");
    private static final Instant END = Instant.parse("2026-08-03T03:00:00Z");

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("일정을 저장하면 enum·Instant·부서 목록(조인 테이블)이 그대로 재조회된다")
    void persistsAndReloadsMapping() {
        Schedule schedule = Schedule.draft(
                10L, "프로젝트 회의", "주간 진행 공유", "개발부", "3층 회의실",
                ScheduleVisibility.DEPARTMENT, START, END);
        schedule.replaceDepartments(List.of(3L, 1L, 2L));

        Schedule saved = scheduleRepository.saveAndFlush(schedule);
        Long id = saved.id();
        entityManager.clear();

        Schedule found = scheduleRepository.findById(id).orElseThrow();
        assertThat(found.status()).isEqualTo(ScheduleStatus.DRAFT);
        assertThat(found.visibilityType()).isEqualTo(ScheduleVisibility.DEPARTMENT);
        assertThat(found.startAt()).isEqualTo(START);
        assertThat(found.endAt()).isEqualTo(END);
        assertThat(found.departmentIds()).containsExactly(1L, 2L, 3L);
        assertThat(found.createdAt()).isNotNull();
        assertThat(found.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 부서로 다시 지정해도 uk_schedule_department를 위반하지 않는다")
    void replacesDepartmentsWithOverlappingSet() {
        Schedule schedule = Schedule.draft(
                10L, "프로젝트 회의", null, null, null,
                ScheduleVisibility.DEPARTMENT, START, END);
        schedule.replaceDepartments(List.of(1L, 2L));
        Long id = scheduleRepository.saveAndFlush(schedule).id();
        entityManager.clear();

        Schedule found = scheduleRepository.findById(id).orElseThrow();
        // 부서 1은 그대로 두고 2를 3으로 바꾼다. clear 후 재삽입이므로 삭제가 삽입보다
        // 먼저 실행되지 않으면 부서 1에서 유니크 제약을 위반한다.
        found.replaceDepartments(List.of(1L, 3L));
        scheduleRepository.saveAndFlush(found);
        entityManager.clear();

        assertThat(scheduleRepository.findById(id).orElseThrow().departmentIds())
                .containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("부서 목록이 비면 빈 목록으로 저장·조회된다")
    void persistsEmptyDepartments() {
        Schedule schedule = Schedule.create(
                10L, "개인 일정", null, null, null,
                ScheduleVisibility.PERSONAL, START, END);

        Long id = scheduleRepository.saveAndFlush(schedule).id();
        entityManager.clear();

        Schedule found = scheduleRepository.findById(id).orElseThrow();
        assertThat(found.status()).isEqualTo(ScheduleStatus.APPROVED);
        assertThat(found.departmentIds()).isEmpty();
    }

    @Test
    @DisplayName("draft 승인 상태 전이가 영속화된다")
    void persistsApproveTransition() {
        Schedule schedule = scheduleRepository.saveAndFlush(Schedule.draft(
                10L, "회의", null, null, null,
                ScheduleVisibility.ALL, START, END));
        schedule.approve();
        Long id = scheduleRepository.saveAndFlush(schedule).id();
        entityManager.clear();

        Schedule found = scheduleRepository.findById(id).orElseThrow();
        assertThat(found.status()).isEqualTo(ScheduleStatus.APPROVED);
    }
}
