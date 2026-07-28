package com.ajt.backend.domain.schedule.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScheduleTest {

    private static final Instant START = Instant.parse("2026-08-03T01:00:00Z");
    private static final Instant END = Instant.parse("2026-08-03T03:00:00Z");

    @Test
    @DisplayName("수동/개인 일정은 생성 즉시 approved 상태다")
    void createIsApproved() {
        Schedule schedule = Schedule.create(
                1L, "회의", "내용", "개발부", "3층", ScheduleVisibility.PERSONAL, START, END);

        assertThat(schedule.status()).isEqualTo(ScheduleStatus.APPROVED);
        assertThat(schedule.title()).isEqualTo("회의");
        assertThat(schedule.visibilityType()).isEqualTo(ScheduleVisibility.PERSONAL);
        assertThat(schedule.startAt()).isEqualTo(START);
        assertThat(schedule.endAt()).isEqualTo(END);
        assertThat(schedule.hasSourceDocument()).isFalse();
    }

    @Test
    @DisplayName("추출 일정은 draft 상태로 생성된다")
    void draftIsDraft() {
        Schedule schedule = Schedule.draft(
                1L, "회의", null, null, null, ScheduleVisibility.DEPARTMENT, START, END);

        assertThat(schedule.status()).isEqualTo(ScheduleStatus.DRAFT);
    }

    @Test
    @DisplayName("draft 일정을 승인하면 approved로 전환된다")
    void approveDraft() {
        Schedule schedule = Schedule.draft(
                1L, "회의", null, null, null, ScheduleVisibility.ALL, START, END);

        schedule.approve();

        assertThat(schedule.status()).isEqualTo(ScheduleStatus.APPROVED);
    }

    @Test
    @DisplayName("draft가 아닌 일정 승인은 예외를 던진다")
    void approveNonDraftFails() {
        Schedule schedule = Schedule.create(
                1L, "회의", null, null, null, ScheduleVisibility.ALL, START, END);

        assertThatThrownBy(schedule::approve)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("종료 시각이 시작 시각보다 빠르면 생성에 실패한다")
    void invalidPeriodFails() {
        assertThatThrownBy(() -> Schedule.create(
                1L, "회의", null, null, null, ScheduleVisibility.ALL, END, START))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("공개 부서 목록은 중복을 제거하고 정렬해 저장한다")
    void replaceDepartmentsDedupAndSort() {
        Schedule schedule = Schedule.create(
                1L, "회의", null, null, null, ScheduleVisibility.DEPARTMENT, START, END);

        schedule.replaceDepartments(List.of(3L, 1L, 3L, 2L));

        assertThat(schedule.departmentIds()).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("linkSource 후 원본문서 보유 여부가 true가 된다")
    void linkSourceMarksHasSource() {
        Schedule schedule = Schedule.draft(
                1L, "회의", null, null, null, ScheduleVisibility.ALL, START, END);

        schedule.linkSource("grp-1", "schedule/grp-1/original.xlsx", "schedule/grp-1/parsed.md");

        assertThat(schedule.hasSourceDocument()).isTrue();
        assertThat(schedule.sourceOriginalPath()).isEqualTo("schedule/grp-1/original.xlsx");
    }
}
