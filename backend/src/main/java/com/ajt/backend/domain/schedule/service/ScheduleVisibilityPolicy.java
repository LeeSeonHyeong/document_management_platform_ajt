package com.ajt.backend.domain.schedule.service;

import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleStatus;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import org.springframework.stereotype.Component;

/**
 * 사원 관점의 일정 열람 판정입니다. 챗봇 질문 처리와 AI 에이전트용 일정 조회가 <b>같은 판정</b>을
 * 쓰도록 한 곳에 모읍니다.
 *
 * <p>두 곳에 복사해 두면 한쪽만 고쳐져 조용히 어긋난다. 실제로 공개 API({@code ScheduleService})는
 * 관리자 예외를 포함한 별도 판정을 갖고 있는데, 이쪽은 <b>관리자 예외가 없다</b> — 챗봇은 질문한
 * 사람이 관리자여도 사원과 같은 근거로 답해야 하고, 초안(DRAFT) 일정은 승인 전이라 답변 근거가
 * 되어서는 안 된다(FR-SCH).
 */
@Component
public class ScheduleVisibilityPolicy {

    /** 질문자가 답변 근거로 쓸 수 있는 일정인지. 승인 여부와 공개 범위를 함께 본다. */
    public boolean isReadableBy(Schedule schedule, Member member) {
        Long departmentId = member.getDepartment() == null ? null : member.getDepartment().getId();
        return isReadableBy(schedule, member.getId(), departmentId);
    }

    public boolean isReadableBy(Schedule schedule, long memberId, Long departmentId) {
        return schedule.status() == ScheduleStatus.APPROVED
                && isVisibleTo(schedule, memberId, departmentId);
    }

    /** 공개 범위만 본다. 승인 여부는 {@link #isReadableBy} 가 함께 판정한다. */
    public boolean isVisibleTo(Schedule schedule, long memberId, Long departmentId) {
        ScheduleVisibility visibility = schedule.visibilityType();
        if (visibility == ScheduleVisibility.ALL) {
            return true;
        }
        if (visibility == ScheduleVisibility.PERSONAL) {
            return schedule.authorId() == memberId;
        }
        return departmentId != null && schedule.departmentIds().contains(departmentId);
    }
}
