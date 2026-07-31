package com.ajt.backend.domain.schedule.service;

import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.question.AiQuestion;
import com.ajt.backend.domain.question.AiQuestionRepository;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 에이전트가 부르는 일정 조회입니다. (GET /internal/v1/schedules, /internal/v1/schedules/{id})
 *
 * <p>공개 API({@code GET /api/v1/schedules})와 달리 로그인 세션이 없으므로 <b>질문 번호로 질문한
 * 사람을 찾아</b> 그 사람 기준으로 거른다. 요청에 실린 값을 믿지 않는다 — 내부 API 키는 "AI
 * 서버다"만 증명하고 "누구 대신 묻는지"는 증명하지 않는다.
 *
 * <p>판정은 {@link ScheduleVisibilityPolicy} 하나만 쓴다. 챗봇 질문 처리와 같은 판정이어야 한다.
 */
@Service
@Transactional(readOnly = true)
public class InternalScheduleQueryService {

    /** 계약이 정한 목록 상한입니다. 넘겨 와도 이 값으로 자릅니다. */
    private static final int MAX_LIMIT = 50;

    /**
     * 기간 경계를 해석하는 시간대입니다.
     *
     * <p>{@code from}·{@code to}는 날짜이고 {@code schedule.start_at}은 UTC다. 에이전트는 사용자
     * 감각(KST)으로 「8월 3일」을 보내는데 그날 오전 일정은 UTC로 8월 2일이라, UTC로 그냥 비교하면
     * 하루 경계의 일정이 조용히 빠진다. 그래서 {@code from} 00:00 KST ~ {@code to} 다음날 00:00
     * KST를 창으로 삼는다.
     */
    private static final ZoneId REQUEST_ZONE = ZoneId.of("Asia/Seoul");

    private final AiQuestionRepository questionRepository;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleVisibilityPolicy visibilityPolicy;

    public InternalScheduleQueryService(
            AiQuestionRepository questionRepository,
            ScheduleRepository scheduleRepository,
            ScheduleVisibilityPolicy visibilityPolicy
    ) {
        this.questionRepository = questionRepository;
        this.scheduleRepository = scheduleRepository;
        this.visibilityPolicy = visibilityPolicy;
    }

    /**
     * 기간 안에서 질문자가 볼 수 있는 일정의 제목·시각·대상입니다. 본문은 싣지 않는다 —
     * 에이전트가 고른 것만 상세로 따로 읽는다.
     *
     * <p>시작 시각이 가까운 순서로 채우고 상한을 넘으면 자른다. 잘랐다는 사실을
     * {@code truncated}로 알린다 — 모르면 에이전트가 「이게 전부다」로 단정한다.
     */
    public ScheduleListResult list(long questionId, LocalDate from, LocalDate to, String keyword, int limit) {
        Member asker = askerOf(questionId);
        if (from == null || to == null) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE_RANGE, "조회 기간을 입력해주세요.");
        }
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_SCHEDULE_RANGE, "조회 시작일이 종료일보다 늦습니다.");
        }
        int capped = Math.min(limit <= 0 ? MAX_LIMIT : limit, MAX_LIMIT);

        Instant windowStart = from.atStartOfDay(REQUEST_ZONE).toInstant();
        Instant windowEndExclusive = to.plusDays(1).atStartOfDay(REQUEST_ZONE).toInstant();

        List<Schedule> readable = scheduleRepository
                .findApprovedByPeriod(windowStart, windowEndExclusive).stream()
                .filter(schedule -> visibilityPolicy.isReadableBy(schedule, asker))
                .filter(schedule -> matchesKeyword(schedule, keyword))
                .toList();

        List<ScheduleListItem> items = readable.stream()
                .limit(capped)
                .map(ScheduleListItem::from)
                .toList();
        return new ScheduleListResult(items, readable.size() > items.size());
    }

    /** 일정 하나의 본문까지입니다. 질문자가 볼 수 없으면 존재를 흘리지 않고 404로 숨긴다. */
    public ScheduleDetail detail(long questionId, long scheduleId) {
        Member asker = askerOf(questionId);
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .filter(found -> visibilityPolicy.isReadableBy(found, asker))
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        return ScheduleDetail.from(schedule);
    }

    /**
     * 질문 번호로 질문한 사람을 찾습니다. 없는 번호는 거절한다 — 아무 번호나 넣어 남의 일정을
     * 조회할 수 없어야 한다.
     */
    private Member askerOf(long questionId) {
        return questionRepository.findById(questionId)
                .map(AiQuestion::getMember)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
    }

    /**
     * 제목 부분 일치입니다. 한국어는 조사가 붙어(「워크샵을」) 단어 단위 비교가 어긋나므로
     * 부분 일치로 본다 — Wiki 검색에서 실측한 성질이다.
     */
    private boolean matchesKeyword(Schedule schedule, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        return schedule.title() != null && schedule.title().contains(keyword.trim());
    }

    /** 목록 응답입니다. {@code truncated}는 상한에 걸려 잘렸는지입니다. */
    public record ScheduleListResult(List<ScheduleListItem> items, boolean truncated) {
    }

    public record ScheduleListItem(
            String scheduleId,
            String title,
            String startAt,
            String endAt,
            String targetText,
            String location
    ) {
        static ScheduleListItem from(Schedule schedule) {
            return new ScheduleListItem(
                    String.valueOf(schedule.id()),
                    schedule.title(),
                    schedule.startAt().toString(),
                    schedule.endAt().toString(),
                    schedule.targetText(),
                    schedule.location()
            );
        }
    }

    public record ScheduleDetail(
            String scheduleId,
            String title,
            String content,
            String startAt,
            String endAt,
            String targetText,
            String location
    ) {
        static ScheduleDetail from(Schedule schedule) {
            return new ScheduleDetail(
                    String.valueOf(schedule.id()),
                    schedule.title(),
                    schedule.content(),
                    schedule.startAt().toString(),
                    schedule.endAt().toString(),
                    schedule.targetText(),
                    schedule.location()
            );
        }
    }
}
