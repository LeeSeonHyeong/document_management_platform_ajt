package com.ajt.backend.domain.schedule.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 일정 상세 응답의 원본문서 노출 규칙(FR-SCH-009) 검증.
 * 사원에게는 원본문서 파일명·다운로드 URL을 노출하지 않고, 관리자에게만 노출한다.
 */
class ScheduleDetailResponseTest {

    private static final Instant START = Instant.parse("2026-08-03T01:00:00Z");
    private static final Instant END = Instant.parse("2026-08-03T03:00:00Z");

    private Schedule scheduleWithSource() {
        Schedule schedule = Schedule.draft(
                1L, "프로젝트 회의", "내용", "개발부", "3층",
                ScheduleVisibility.DEPARTMENT, START, END);
        schedule.linkSource(
                "sg-1",
                "schedule-sources/sg-1/original/source.pdf",
                "회의록.pdf",
                "schedule-sources/sg-1/parsed/content.md");
        return schedule;
    }

    @Test
    @DisplayName("관리자에게는 원본문서(파일명·다운로드 URL)를 노출한다")
    void adminSeesSourceDocument() {
        ScheduleDetailResponse response = ScheduleDetailResponse.from(scheduleWithSource(), true);

        assertThat(response.sourceDocument()).isNotNull();
        assertThat(response.sourceDocument().originalFileName()).isEqualTo("회의록.pdf");
        assertThat(response.sourceDocument().sourceFileUrl()).contains("/source-file");
    }

    @Test
    @DisplayName("사원에게는 원본문서를 노출하지 않는다 (FR-SCH-009)")
    void employeeDoesNotSeeSourceDocument() {
        ScheduleDetailResponse response = ScheduleDetailResponse.from(scheduleWithSource(), false);

        assertThat(response.sourceDocument()).isNull();
    }

    @Test
    @DisplayName("원본문서가 없는 일정은 관리자여도 sourceDocument가 null이다")
    void noSourceMeansNull() {
        Schedule manual = Schedule.create(
                1L, "회식", null, null, null, ScheduleVisibility.ALL, START, END);

        assertThat(ScheduleDetailResponse.from(manual, true).sourceDocument()).isNull();
    }
}
