import { useMemo } from 'react'
import { format } from 'date-fns'
import { cn } from '@/shared/lib/cn'
import { SCHEDULE_VISIBILITY } from '@/shared/constants/enums'
import { layoutMonth, WEEKDAY_LABELS } from '@/features/schedule/calendarMatrix'

// 밴드 레이아웃 상수(px).
const DAY_NUM_H = 28
const LANE_H = 22
const LANE_GAP = 3
const MAX_LANES = 3

// 공개 범위별 밴드 색.
const VIS_STYLES = {
  [SCHEDULE_VISIBILITY.ALL]: 'bg-primary-500 text-white',
  [SCHEDULE_VISIBILITY.DEPARTMENT]: 'bg-sky-500 text-white',
  [SCHEDULE_VISIBILITY.PERSONAL]: 'bg-emerald-500 text-white',
}

function pct(n) {
  return `${(n / 7) * 100}%`
}

// 월간 캘린더 + 멀티데이 밴드. 사원 통합 달력·관리자 일정 관리에서 공용으로 쓴다.
export default function CalendarGrid({ monthDate, events = [], selectedDate, onSelectDate, onEventClick }) {
  const { weeks } = useMemo(
    () => layoutMonth(monthDate, events, { maxLanes: MAX_LANES }),
    [monthDate, events],
  )

  const weekMinHeight = DAY_NUM_H + MAX_LANES * (LANE_H + LANE_GAP) + 20

  return (
    <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
      {/* 요일 헤더 */}
      <div className="grid grid-cols-7 border-b border-slate-100 bg-slate-50">
        {WEEKDAY_LABELS.map((label, i) => (
          <div
            key={label}
            className={cn(
              'py-2 text-center text-xs font-medium',
              i === 0 ? 'text-rose-500' : i === 6 ? 'text-sky-500' : 'text-slate-500',
            )}
          >
            {label}
          </div>
        ))}
      </div>

      {/* 주 단위 행 */}
      {weeks.map((week, wi) => (
        <div
          key={wi}
          className="relative border-b border-slate-100 last:border-b-0"
          style={{ minHeight: weekMinHeight }}
        >
          {/* 배경: 날짜 셀 7칸 */}
          <div className="absolute inset-0 grid grid-cols-7">
            {week.days.map(({ date, isCurrentMonth, isToday }) => {
              const selected = selectedDate && format(selectedDate, 'yyyy-MM-dd') === format(date, 'yyyy-MM-dd')
              const dow = date.getDay()
              return (
                <button
                  key={date.toISOString()}
                  type="button"
                  onClick={() => onSelectDate?.(date)}
                  className={cn(
                    'focus-ring relative cursor-pointer border-r border-slate-100 text-left last:border-r-0',
                    !isCurrentMonth && 'bg-slate-50/60',
                    selected && 'z-10 bg-primary-50/60 ring-2 ring-inset ring-primary-500',
                  )}
                  style={{ height: '100%' }}
                >
                  <span
                    className={cn(
                      'absolute left-1.5 top-1 inline-flex size-6 items-center justify-center rounded-full text-xs',
                      isToday && 'bg-primary-600 font-semibold text-white',
                      !isToday && !isCurrentMonth && 'text-slate-300',
                      !isToday && isCurrentMonth && dow === 0 && 'text-rose-500',
                      !isToday && isCurrentMonth && dow === 6 && 'text-sky-500',
                      !isToday && isCurrentMonth && dow !== 0 && dow !== 6 && 'text-slate-600',
                    )}
                  >
                    {format(date, 'd')}
                  </span>
                </button>
              )
            })}
          </div>

          {/* 오버레이: 멀티데이 밴드 */}
          <div className="pointer-events-none absolute inset-x-0 z-20" style={{ top: DAY_NUM_H }}>
            {week.segments.map((seg) => (
              <button
                key={`${seg.event.id}-${seg.colStart}`}
                type="button"
                onClick={onEventClick ? (event) => {
                  event.stopPropagation()
                  onEventClick(seg.event)
                } : undefined}
                title={seg.event.title}
                className={cn(
                  'absolute flex h-5 items-center truncate px-1.5 text-[11px] font-medium',
                  onEventClick && 'pointer-events-auto',
                  VIS_STYLES[seg.event.visibilityType] ?? 'bg-slate-500 text-white',
                  seg.isStart ? 'rounded-l-md' : 'rounded-l-none',
                  seg.isEnd ? 'rounded-r-md' : 'rounded-r-none',
                )}
                style={{
                  left: `calc(${pct(seg.colStart)} + 3px)`,
                  width: `calc(${pct(seg.colSpan)} - 6px)`,
                  top: seg.lane * (LANE_H + LANE_GAP),
                }}
              >
                {(seg.isStart || seg.colStart === 0) && <span className="truncate">{seg.event.title}</span>}
              </button>
            ))}

            {/* 레인 초과분 "+N" */}
            {week.overflowByCol.map((count, ci) =>
              count > 0 ? (
                <span
                  key={ci}
                  className="absolute text-[10px] font-medium text-slate-400"
                  style={{ left: `calc(${pct(ci)} + 4px)`, top: MAX_LANES * (LANE_H + LANE_GAP) }}
                >
                  +{count}
                </span>
              ) : null,
            )}
          </div>
        </div>
      ))}
    </div>
  )
}
