package com.ajt.backend.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.question.AiQuestion;
import com.ajt.backend.domain.question.AiQuestionRepository;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.domain.schedule.service.InternalScheduleQueryService.ScheduleDetail;
import com.ajt.backend.domain.schedule.service.InternalScheduleQueryService.ScheduleListItem;
import com.ajt.backend.domain.schedule.service.InternalScheduleQueryService.ScheduleListResult;
import com.ajt.backend.global.error.BusinessException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("AI 에이전트용 일정 조회 — 질문 번호로 권한을 판정한다")
class InternalScheduleQueryServiceTest {

    private final AiQuestionRepository questionRepository = mock(AiQuestionRepository.class);
    private final ScheduleRepository scheduleRepository = mock(ScheduleRepository.class);
    private final InternalScheduleQueryService service = new InternalScheduleQueryService(
            questionRepository, scheduleRepository, new ScheduleVisibilityPolicy());

    @Test
    @DisplayName("질문 번호로 질문한 사람을 찾아 그 사람이 볼 수 있는 일정만 돌려준다")
    void listFiltersByTheAskingMember() throws Exception {
        AiQuestion question = questionAskedBy(member(10L, 1L));
        List<Schedule> found = List.of(
                schedule(31L, "전사 워크샵", ScheduleVisibility.ALL, 99L, List.of()),
                schedule(32L, "인사부 회의", ScheduleVisibility.DEPARTMENT, 99L, List.of(2L)));
        given(questionRepository.findById(500L)).willReturn(Optional.of(question));
        given(scheduleRepository.findApprovedByPeriod(any(), any())).willReturn(found);

        ScheduleListResult result = service.list(500L,
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"), null, 50);

        assertThat(result.items()).extracting(ScheduleListItem::title)
                .containsExactly("전사 워크샵");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("소속 부서 공개 일정은 보이고, 남의 개인 일정은 보이지 않는다")
    void departmentIsVisibleAndOthersPersonalIsNot() throws Exception {
        AiQuestion question = questionAskedBy(member(10L, 1L));
        List<Schedule> found = List.of(
                schedule(31L, "개발부 회의", ScheduleVisibility.DEPARTMENT, 99L, List.of(1L)),
                schedule(32L, "남의 휴가", ScheduleVisibility.PERSONAL, 99L, List.of()),
                schedule(33L, "내 휴가", ScheduleVisibility.PERSONAL, 10L, List.of()));
        given(questionRepository.findById(500L)).willReturn(Optional.of(question));
        given(scheduleRepository.findApprovedByPeriod(any(), any())).willReturn(found);

        ScheduleListResult result = service.list(500L,
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"), null, 50);

        assertThat(result.items()).extracting(ScheduleListItem::title)
                .containsExactly("개발부 회의", "내 휴가");
    }

    @Test
    @DisplayName("제목 부분 일치로 좁힌다 — 조사가 붙어도 걸린다")
    void keywordMatchesPartOfTheTitle() throws Exception {
        AiQuestion question = questionAskedBy(member(10L, 1L));
        List<Schedule> found = List.of(
                schedule(31L, "하계 워크샵을 안내합니다", ScheduleVisibility.ALL, 99L, List.of()),
                schedule(32L, "월간 회의", ScheduleVisibility.ALL, 99L, List.of()));
        given(questionRepository.findById(500L)).willReturn(Optional.of(question));
        given(scheduleRepository.findApprovedByPeriod(any(), any())).willReturn(found);

        ScheduleListResult result = service.list(500L,
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"), "워크샵", 50);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("하계 워크샵을 안내합니다");
    }

    @Test
    @DisplayName("상한을 넘으면 가까운 날짜부터 남기고 잘렸다고 알린다")
    void listTruncatesAndSaysSo() throws Exception {
        AiQuestion question = questionAskedBy(member(10L, 1L));
        List<Schedule> found = manyApprovedSchedules(120);
        given(questionRepository.findById(500L)).willReturn(Optional.of(question));
        given(scheduleRepository.findApprovedByPeriod(any(), any())).willReturn(found);

        ScheduleListResult result = service.list(500L,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), null, 50);

        assertThat(result.items()).hasSize(50);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    @DisplayName("limit이 상한을 넘겨 와도 50으로 자른다")
    void limitIsCappedAtFifty() throws Exception {
        AiQuestion question = questionAskedBy(member(10L, 1L));
        List<Schedule> found = manyApprovedSchedules(120);
        given(questionRepository.findById(500L)).willReturn(Optional.of(question));
        given(scheduleRepository.findApprovedByPeriod(any(), any())).willReturn(found);

        ScheduleListResult result = service.list(500L,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), null, 999);

        assertThat(result.items()).hasSize(50);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    @DisplayName("기간 경계는 KST 하루로 넓혀 조회한다 — UTC로 그냥 비교하면 그날 오전 일정이 빠진다")
    void periodWindowIsInterpretedInKst() throws Exception {
        AiQuestion question = questionAskedBy(member(10L, 1L));
        given(questionRepository.findById(500L)).willReturn(Optional.of(question));
        given(scheduleRepository.findApprovedByPeriod(any(), any())).willReturn(List.of());

        service.list(500L, LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-03"), null, 50);

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endExclusive = ArgumentCaptor.forClass(Instant.class);
        org.mockito.BDDMockito.then(scheduleRepository).should()
                .findApprovedByPeriod(start.capture(), endExclusive.capture());
        // 8월 3일 00:00 KST == 8월 2일 15:00 UTC
        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-08-02T15:00:00Z"));
        assertThat(endExclusive.getValue()).isEqualTo(Instant.parse("2026-08-03T15:00:00Z"));
    }

    @Test
    @DisplayName("없는 질문 번호로 부르면 거절한다 — 아무 번호나 넣어 조회할 수 없다")
    void unknownQuestionIsRejected() {
        given(questionRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(999L,
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"), null, 50))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("질문");
    }

    @Test
    @DisplayName("조회 시작일이 종료일보다 늦으면 거절한다")
    void invertedPeriodIsRejected() throws Exception {
        AiQuestion question = questionAskedBy(member(10L, 1L));
        given(questionRepository.findById(500L)).willReturn(Optional.of(question));

        assertThatThrownBy(() -> service.list(500L,
                LocalDate.parse("2026-08-31"), LocalDate.parse("2026-08-01"), null, 50))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("상세는 본문을 포함한다")
    void detailIncludesContent() throws Exception {
        AiQuestion question = questionAskedBy(member(10L, 1L));
        Schedule visible = schedule(31L, "전사 워크샵", ScheduleVisibility.ALL, 99L, List.of());
        given(visible.content()).willReturn("전사 워크샵 안내");
        given(visible.targetText()).willReturn("전사");
        given(visible.location()).willReturn("본사");
        given(questionRepository.findById(500L)).willReturn(Optional.of(question));
        given(scheduleRepository.findById(31L)).willReturn(Optional.of(visible));

        ScheduleDetail detail = service.detail(500L, 31L);

        assertThat(detail.scheduleId()).isEqualTo("31");
        assertThat(detail.content()).isEqualTo("전사 워크샵 안내");
        assertThat(detail.startAt()).isEqualTo("2026-08-03T01:00:00Z");
    }

    @Test
    @DisplayName("권한 밖 일정의 상세는 못 읽는다")
    void detailOfAnInvisibleScheduleIsNotFound() throws Exception {
        AiQuestion question = questionAskedBy(member(10L, 1L));
        Schedule hrOnly = schedule(32L, "인사부 회의", ScheduleVisibility.DEPARTMENT, 99L, List.of(2L));
        given(questionRepository.findById(500L)).willReturn(Optional.of(question));
        given(scheduleRepository.findById(32L)).willReturn(Optional.of(hrOnly));

        assertThatThrownBy(() -> service.detail(500L, 32L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("일정");
    }

    @Test
    @DisplayName("승인 전 일정의 상세는 못 읽는다 — 목록과 같은 기준이다")
    void detailOfADraftScheduleIsNotFound() throws Exception {
        AiQuestion question = questionAskedBy(member(10L, 1L));
        Schedule draft = schedule(33L, "초안 워크샵", ScheduleVisibility.ALL, 99L, List.of());
        given(draft.status()).willReturn(com.ajt.backend.domain.schedule.model.ScheduleStatus.DRAFT);
        given(questionRepository.findById(500L)).willReturn(Optional.of(question));
        given(scheduleRepository.findById(33L)).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.detail(500L, 33L))
                .isInstanceOf(BusinessException.class);
    }

    private List<Schedule> manyApprovedSchedules(int count) {
        List<Schedule> schedules = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            schedules.add(schedule(1000L + index, "일정 " + index, ScheduleVisibility.ALL, 99L, List.of()));
        }
        return schedules;
    }

    private Schedule schedule(
            long id,
            String title,
            ScheduleVisibility visibility,
            long authorId,
            List<Long> departmentIds
    ) {
        Schedule schedule = mock(Schedule.class);
        given(schedule.id()).willReturn(id);
        given(schedule.title()).willReturn(title);
        given(schedule.visibilityType()).willReturn(visibility);
        given(schedule.status()).willReturn(com.ajt.backend.domain.schedule.model.ScheduleStatus.APPROVED);
        given(schedule.authorId()).willReturn(authorId);
        given(schedule.departmentIds()).willReturn(departmentIds);
        given(schedule.startAt()).willReturn(Instant.parse("2026-08-03T01:00:00Z"));
        given(schedule.endAt()).willReturn(Instant.parse("2026-08-03T03:00:00Z"));
        return schedule;
    }

    private Member member(long id, Long departmentId) {
        Member member = mock(Member.class);
        given(member.getId()).willReturn(id);
        if (departmentId != null) {
            Department department = mock(Department.class);
            given(department.getId()).willReturn(departmentId);
            given(member.getDepartment()).willReturn(department);
        }
        return member;
    }

    private AiQuestion questionAskedBy(Member member) throws Exception {
        AiQuestion question = AiQuestion.create(member, "chat-1", "다음 워크샵 언제야?", null, false, null);
        Field field = question.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(question, 500L);
        return question;
    }
}
