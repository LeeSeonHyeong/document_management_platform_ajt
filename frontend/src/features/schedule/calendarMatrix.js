import {
  startOfMonth,
  endOfMonth,
  startOfWeek,
  endOfWeek,
  eachDayOfInterval,
  startOfDay,
  differenceInCalendarDays,
  isSameDay,
  isSameMonth,
  isToday,
} from 'date-fns'

// 월간 캘린더의 그리드와 멀티데이 일정 밴드 레이아웃을 계산한다.
// 주 경계를 넘는 일정을 주별 세그먼트로 잘라 레인(층)을 배정하는 것이 핵심(최고 난도).
// weekStartsOn: 0(일요일 시작).

const WEEK_STARTS_ON = 0

function toDay(value) {
  return startOfDay(value instanceof Date ? value : new Date(value))
}

// 주 하나에 대해 세그먼트를 만들고 겹치지 않도록 레인을 배정한다.
function layoutWeek(weekDays, events) {
  const weekStart = weekDays[0]
  const weekEnd = weekDays[6]

  // 이 주와 겹치는 일정만 세그먼트로 변환.
  const segments = []
  for (const event of events) {
    const eStart = toDay(event.start)
    const eEnd = toDay(event.end)
    if (eEnd < weekStart || eStart > weekEnd) continue

    const segStart = eStart < weekStart ? weekStart : eStart
    const segEnd = eEnd > weekEnd ? weekEnd : eEnd
    segments.push({
      event,
      colStart: differenceInCalendarDays(segStart, weekStart), // 0~6
      colSpan: differenceInCalendarDays(segEnd, segStart) + 1, // 1~7
      isStart: isSameDay(eStart, segStart), // 일정이 이 주에서 실제 시작
      isEnd: isSameDay(eEnd, segEnd), // 일정이 이 주에서 실제 종료
      _sortStart: eStart.getTime(),
    })
  }

  // 시작 열 오름차순, 같은 열이면 긴 일정 먼저 → 레인 배정이 안정적.
  segments.sort((a, b) => a.colStart - b.colStart || b.colSpan - a.colSpan || a._sortStart - b._sortStart)

  // 레인별로 점유한 열 구간을 기록하며 겹치지 않는 첫 레인에 배치.
  const lanes = []
  for (const seg of segments) {
    const from = seg.colStart
    const to = seg.colStart + seg.colSpan - 1
    let laneIndex = lanes.findIndex((ranges) => ranges.every((r) => to < r.from || from > r.to))
    if (laneIndex === -1) {
      laneIndex = lanes.length
      lanes.push([])
    }
    lanes[laneIndex].push({ from, to })
    seg.lane = laneIndex
  }

  return segments
}

/**
 * 월 전체 레이아웃을 계산한다.
 * @param baseDate 표시할 달의 임의 날짜
 * @param events [{ id, title, start, end, ... }]
 * @param maxLanes 셀에 표시할 최대 레인 수(초과분은 "+N"으로 접힘)
 * @returns { weeks: [{ days:[{date,isCurrentMonth,isToday}], segments:[{...,lane}], overflowByCol:[n x7] }] }
 */
export function layoutMonth(baseDate, events = [], { maxLanes = 3 } = {}) {
  const monthStart = startOfMonth(baseDate)
  const gridStart = startOfWeek(monthStart, { weekStartsOn: WEEK_STARTS_ON })
  const gridEnd = endOfWeek(endOfMonth(baseDate), { weekStartsOn: WEEK_STARTS_ON })
  const allDays = eachDayOfInterval({ start: gridStart, end: gridEnd })

  const weeks = []
  for (let i = 0; i < allDays.length; i += 7) {
    const weekDays = allDays.slice(i, i + 7)
    const segments = layoutWeek(weekDays, events)

    // 레인 초과분은 열별 카운트로 접어서 "+N"으로 보여준다.
    const visible = segments.filter((s) => s.lane < maxLanes)
    const hidden = segments.filter((s) => s.lane >= maxLanes)
    const overflowByCol = Array(7).fill(0)
    for (const seg of hidden) {
      for (let c = seg.colStart; c < seg.colStart + seg.colSpan; c++) overflowByCol[c] += 1
    }

    weeks.push({
      days: weekDays.map((date) => ({
        date,
        isCurrentMonth: isSameMonth(date, monthStart),
        isToday: isToday(date),
      })),
      segments: visible,
      overflowByCol,
    })
  }

  return { weeks, monthStart }
}

export const WEEKDAY_LABELS = ['일', '월', '화', '수', '목', '금', '토']
