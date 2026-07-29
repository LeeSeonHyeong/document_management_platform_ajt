import { useMemo, useState } from 'react'
import {
  startOfMonth,
  endOfMonth,
  startOfWeek,
  endOfWeek,
  startOfDay,
  endOfDay,
  addMonths,
  format,
  isWithinInterval,
  getDate,
  getDaysInMonth,
  setDate,
} from 'date-fns'
import { ko } from 'date-fns/locale'
import {
  ChevronLeft,
  ChevronRight,
  Plus,
  Sparkles,
  MapPin,
  CalendarPlus,
  AlertTriangle,
  RotateCw,
} from 'lucide-react'
import CalendarGrid from '@/components/calendar/CalendarGrid'
import ScheduleFormModal from '@/components/calendar/ScheduleFormModal'
import Fab from '@/components/layout/Fab'
import { Button, Card, EmptyState, Badge } from '@/components/ui'
import { useToast } from '@/components/ui'
import { useSchedules } from '@/features/schedule/useSchedules'
import {
  SCHEDULE_VISIBILITY,
  SCHEDULE_VISIBILITY_LABELS,
} from '@/shared/constants/enums'

const VIS_TONE = {
  [SCHEDULE_VISIBILITY.ALL]: 'primary',
  [SCHEDULE_VISIBILITY.DEPARTMENT]: 'info',
  [SCHEDULE_VISIBILITY.PERSONAL]: 'success',
}

// 일정이 특정 날짜에 걸치는지.
function occursOn(event, date) {
  return isWithinInterval(date, { start: startOfDay(event.start), end: endOfDay(event.end) })
}

function timeRange(event) {
  const sameDay = format(event.start, 'yyyy-MM-dd') === format(event.end, 'yyyy-MM-dd')
  return sameDay
    ? `${format(event.start, 'M.d HH:mm')} – ${format(event.end, 'HH:mm')}`
    : `${format(event.start, 'M.d HH:mm')} – ${format(event.end, 'M.d HH:mm')}`
}

function ScheduleRow({ event, onClick }) {
  return (
    <button
      type="button"
      onClick={() => onClick(event)}
      className="focus-ring flex w-full items-start gap-3 rounded-lg border border-slate-100 px-3 py-2.5 text-left hover:bg-slate-50"
    >
      <span
        className={
          'mt-1 size-2.5 shrink-0 rounded-full ' +
          (event.visibilityType === SCHEDULE_VISIBILITY.ALL
            ? 'bg-primary-500'
            : event.visibilityType === SCHEDULE_VISIBILITY.DEPARTMENT
              ? 'bg-sky-500'
              : 'bg-emerald-500')
        }
      />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <p className="truncate text-sm font-medium text-slate-800">{event.title}</p>
          <Badge tone={VIS_TONE[event.visibilityType]}>
            {SCHEDULE_VISIBILITY_LABELS[event.visibilityType]}
          </Badge>
        </div>
        <p className="mt-0.5 text-xs text-slate-500">{timeRange(event)}</p>
        {event.location && (
          <p className="mt-0.5 flex items-center gap-1 text-xs text-slate-400">
            <MapPin className="size-3" />
            {event.location}
          </p>
        )}
      </div>
    </button>
  )
}

// S1 홈·통합 달력. 권한 내 전체·부서 일정 + 본인 개인 일정을 한 달력에서 본다.
export default function HomePage() {
  const toast = useToast()
  const [month, setMonth] = useState(() => startOfMonth(new Date()))
  const [selectedDate, setSelectedDate] = useState(() => new Date())
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState(null)

  // 보이는 그리드 전체 범위로 조회(앞뒤 달 걸침 포함).
  const range = useMemo(
    () => ({
      startDate: format(startOfWeek(startOfMonth(month), { weekStartsOn: 0 }), 'yyyy-MM-dd'),
      endDate: format(endOfWeek(endOfMonth(month), { weekStartsOn: 0 }), 'yyyy-MM-dd'),
    }),
    [month],
  )

  const { data: events = [], isLoading, isError, refetch, isFetching } = useSchedules(range)

  const daySchedules = useMemo(
    () => events.filter((e) => occursOn(e, selectedDate)).sort((a, b) => a.start - b.start),
    [events, selectedDate],
  )

  const myMonthSchedules = useMemo(() => {
    const monthStart = startOfMonth(month)
    const monthEnd = endOfMonth(month)
    // 일정 기간이 이번 달과 겹치면 포함(월 경계에 걸친 일정 누락 방지).
    return events
      .filter((e) => e.visibilityType === SCHEDULE_VISIBILITY.PERSONAL)
      .filter((e) => e.end >= monthStart && e.start <= monthEnd)
      .sort((a, b) => a.start - b.start)
  }, [events, month])

  // 월 이동 시 선택 날짜도 새 달의 같은 일(길이 초과 시 말일로 보정)로 옮긴다.
  const moveMonth = (delta) => {
    const next = addMonths(month, delta)
    const day = Math.min(getDate(selectedDate), getDaysInMonth(next))
    setMonth(next)
    setSelectedDate(setDate(next, day))
  }

  const openCreate = () => {
    setEditing(null)
    setModalOpen(true)
  }

  const handleEventClick = (event) => {
    if (event.visibilityType === SCHEDULE_VISIBILITY.PERSONAL) {
      setEditing(event)
      setModalOpen(true)
    } else {
      toast.info(event.title, timeRange(event))
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h1 className="text-xl font-bold text-slate-800">{format(month, 'yyyy년 M월')}</h1>
          <div className="flex items-center">
            <button
              type="button"
              onClick={() => moveMonth(-1)}
              className="focus-ring rounded-lg p-1.5 text-slate-500 hover:bg-slate-100"
              aria-label="이전 달"
            >
              <ChevronLeft className="size-5" />
            </button>
            <button
              type="button"
              onClick={() => {
                setMonth(startOfMonth(new Date()))
                setSelectedDate(new Date())
              }}
              className="focus-ring rounded-lg px-2.5 py-1 text-sm text-slate-600 hover:bg-slate-100"
            >
              오늘
            </button>
            <button
              type="button"
              onClick={() => moveMonth(1)}
              className="focus-ring rounded-lg p-1.5 text-slate-500 hover:bg-slate-100"
              aria-label="다음 달"
            >
              <ChevronRight className="size-5" />
            </button>
          </div>
        </div>
        <Button onClick={openCreate}>
          <Plus className="size-4" />
          일정 추가
        </Button>
      </div>

      {isError ? (
        <Card>
          <div className="flex flex-col items-center gap-3 px-6 py-16 text-center">
            <span className="flex size-12 items-center justify-center rounded-full bg-rose-50 text-rose-500">
              <AlertTriangle className="size-6" />
            </span>
            <div className="space-y-1">
              <p className="text-sm font-medium text-slate-700">일정을 불러오지 못했어요</p>
              <p className="text-xs text-slate-400">네트워크 상태를 확인하고 다시 시도해 주세요.</p>
            </div>
            <Button variant="outline" onClick={() => refetch()} loading={isFetching}>
              <RotateCw className="size-4" />
              다시 시도
            </Button>
          </div>
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_20rem]">
        <CalendarGrid
          monthDate={month}
          events={events}
          selectedDate={selectedDate}
          onSelectDate={setSelectedDate}
          onEventClick={handleEventClick}
        />

        <div className="space-y-4">
          <Card>
            <div className="border-b border-slate-100 px-4 py-3">
              <h2 className="text-sm font-semibold text-slate-800">
                {format(selectedDate, 'M월 d일 (EEE)', { locale: ko })} 일정
              </h2>
            </div>
            <div className="space-y-2 p-3">
              {isLoading ? (
                <p className="px-1 py-6 text-center text-sm text-slate-400">불러오는 중…</p>
              ) : daySchedules.length === 0 ? (
                <EmptyState title="일정이 없어요" description="이 날에는 등록된 일정이 없습니다." className="py-8" />
              ) : (
                daySchedules.map((e) => <ScheduleRow key={e.id} event={e} onClick={handleEventClick} />)
              )}
            </div>
          </Card>

          <Card>
            <div className="border-b border-slate-100 px-4 py-3">
              <h2 className="text-sm font-semibold text-slate-800">이번 달 내 일정</h2>
            </div>
            <div className="space-y-2 p-3">
              {myMonthSchedules.length === 0 ? (
                <p className="px-1 py-6 text-center text-sm text-slate-400">개인 일정이 없어요</p>
              ) : (
                myMonthSchedules.map((e) => <ScheduleRow key={e.id} event={e} onClick={handleEventClick} />)
              )}
            </div>
          </Card>
        </div>
        </div>
      )}

      <Fab
        items={[
          {
            icon: Sparkles,
            label: 'AI 검색 어시스턴트',
            tone: 'secondary',
            onClick: () => toast.info('AI 검색 어시스턴트', '챗봇 화면은 이후 스토리에서 제공됩니다 (S15P11B106-88).'),
          },
          { icon: CalendarPlus, label: '일정 추가', onClick: openCreate },
        ]}
      />

      <ScheduleFormModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        initial={editing}
        defaultDate={selectedDate}
      />
    </div>
  )
}
