import { useMemo, useState } from 'react'
import {
  startOfMonth,
  endOfMonth,
  startOfWeek,
  endOfWeek,
  startOfDay,
  endOfDay,
  eachDayOfInterval,
  addMonths,
  format,
  isWithinInterval,
  isToday,
  getDate,
  getDaysInMonth,
  setDate,
} from 'date-fns'
import { ko } from 'date-fns/locale'
import {
  ChevronLeft,
  ChevronRight,
  Plus,
  MapPin,
  FileText,
  AlertTriangle,
  RotateCw,
} from 'lucide-react'
import CalendarGrid from '@/components/calendar/CalendarGrid'
import AdminScheduleFormModal from '@/components/calendar/AdminScheduleFormModal'
import ScheduleDraftDetailModal from '@/components/calendar/ScheduleDraftDetailModal'
import { Button, Card, EmptyState, Badge, Tabs, DataTable } from '@/components/ui'
import { cn } from '@/shared/lib/cn'
import { useSchedules } from '@/features/schedule/useSchedules'
import { useDepartments } from '@/features/department/useDepartments'
import { filterByDepartmentTab } from '@/features/schedule/adminFilters'
import { SCHEDULE_VISIBILITY } from '@/shared/constants/enums'

const ALL_TAB = 'all'

// 월/주/일 뷰 전환. 월은 캘린더 그리드, 주·일은 같은 일정 데이터를 날짜별 목록으로 좁혀 보여준다.
const VIEW_MODES = [
  { value: 'month', label: '월' },
  { value: 'week', label: '주' },
  { value: 'day', label: '일' },
]

// 공개 범위별 강조색(캘린더 밴드 색과 맞춘다).
const ACCENT = {
  [SCHEDULE_VISIBILITY.ALL]: 'bg-primary-500',
  [SCHEDULE_VISIBILITY.DEPARTMENT]: 'bg-sky-500',
  [SCHEDULE_VISIBILITY.PERSONAL]: 'bg-emerald-500',
}

// 일정이 특정 날짜에 걸치는지(멀티데이 포함).
function occursOn(event, date) {
  return isWithinInterval(date, { start: startOfDay(event.start), end: endOfDay(event.end) })
}

function timeRange(event) {
  const sameDay = format(event.start, 'yyyy-MM-dd') === format(event.end, 'yyyy-MM-dd')
  return sameDay
    ? `${format(event.start, 'HH:mm')} – ${format(event.end, 'HH:mm')}`
    : `${format(event.start, 'M.d HH:mm')} – ${format(event.end, 'M.d HH:mm')}`
}

// 출처 문서 파일명. 상세 응답(sourceDocument)이 실려 있을 때만 노출한다.
// TODO(API): GET /api/v1/schedules 목록 응답에는 sourceDocument(originalFileName)가 없다.
// 파일명·미리보기는 GET /api/v1/schedules/:scheduleId 연동 후에만 표시할 수 있어, 없으면 대체 문구를 쓴다.
function sourceFileName(event) {
  return event.raw?.sourceDocument?.originalFileName ?? null
}

// 선택 날짜 상세 패널의 일정 카드.
function DayScheduleCard({ event, onClick }) {
  const fileName = sourceFileName(event)
  return (
    <button
      type="button"
      onClick={() => onClick(event)}
      className="focus-ring flex w-full gap-2.5 rounded-lg bg-slate-50 px-3 py-2.5 text-left hover:bg-slate-100"
    >
      <span
        className={cn('w-0.5 shrink-0 rounded-full', ACCENT[event.visibilityType] ?? 'bg-slate-400')}
      />
      <div className="min-w-0 flex-1">
        <p className="text-xs font-medium text-slate-500">{timeRange(event)}</p>
        <p className="mt-0.5 truncate text-sm font-semibold text-slate-800">{event.title}</p>
        {event.location && (
          <p className="mt-1 flex items-center gap-1 text-xs text-slate-400">
            <MapPin className="size-3 shrink-0" />
            <span className="truncate">{event.location}</span>
          </p>
        )}
        {fileName && (
          <p className="mt-0.5 flex items-center gap-1 text-xs text-slate-400">
            <FileText className="size-3 shrink-0" />
            <span className="truncate">{fileName}</span>
          </p>
        )}
      </div>
    </button>
  )
}

// 주·일 뷰. 날짜별로 해당 일정만 좁혀 나열한다(시간 그리드는 두지 않는다).
function AgendaView({ days, events, selectedDate, onSelectDate, onEventClick }) {
  return (
    <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
      {days.map((date) => {
        const dayEvents = events
          .filter((e) => occursOn(e, date))
          .sort((a, b) => a.start - b.start)
        const selected = format(date, 'yyyy-MM-dd') === format(selectedDate, 'yyyy-MM-dd')
        return (
          <div
            key={date.toISOString()}
            className={cn(
              'border-b border-slate-100 last:border-b-0',
              selected && 'bg-primary-50/40',
            )}
          >
            <button
              type="button"
              onClick={() => onSelectDate(date)}
              className="focus-ring flex w-full items-center justify-between px-4 py-2.5 text-left"
            >
              <span
                className={cn(
                  'text-sm font-medium',
                  isToday(date) ? 'text-primary-700' : 'text-slate-700',
                )}
              >
                {format(date, 'M월 d일 (EEE)', { locale: ko })}
                {isToday(date) && ' · 오늘'}
              </span>
              <span className="text-xs text-slate-400">
                {dayEvents.length > 0 ? `일정 ${dayEvents.length}건` : '일정 없음'}
              </span>
            </button>
            {dayEvents.length > 0 && (
              <div className="space-y-1.5 px-4 pb-3">
                {dayEvents.map((e) => (
                  <DayScheduleCard key={e.id} event={e} onClick={onEventClick} />
                ))}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

// 5R 관리자 일정 관리·초안 검수. 승인된 일정은 달력에, 초안은 하단 승인 대기 목록 표에 표시한다.
export default function AdminSchedulePage() {
  const [month, setMonth] = useState(() => startOfMonth(new Date()))
  const [selectedDate, setSelectedDate] = useState(() => new Date())
  const [viewMode, setViewMode] = useState('month')
  const [deptTab, setDeptTab] = useState(ALL_TAB)
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [draftDetail, setDraftDetail] = useState(null)

  const range = useMemo(
    () => ({
      startDate: format(startOfWeek(startOfMonth(month), { weekStartsOn: 0 }), 'yyyy-MM-dd'),
      endDate: format(endOfWeek(endOfMonth(month), { weekStartsOn: 0 }), 'yyyy-MM-dd'),
    }),
    [month],
  )

  const { data: departments = [] } = useDepartments()
  const approvedQuery = useSchedules({ ...range, status: 'approved' })
  const draftQuery = useSchedules({ status: 'draft' })

  const deptId = deptTab === ALL_TAB ? null : deptTab
  const approvedEvents = useMemo(
    () => filterByDepartmentTab(approvedQuery.data ?? [], deptId),
    [approvedQuery.data, deptId],
  )
  const draftEvents = useMemo(
    () =>
      // 전체 탭에서는 filterByDepartmentTab이 입력 배열을 그대로 돌려주므로,
      // 쿼리 캐시 배열을 제자리 정렬하지 않도록 복사한 뒤 정렬한다.
      [...filterByDepartmentTab(draftQuery.data ?? [], deptId)].sort((a, b) => a.start - b.start),
    [draftQuery.data, deptId],
  )

  // 선택 날짜에 걸치는 승인 일정(우측 상세 패널용).
  const daySchedules = useMemo(
    () => approvedEvents.filter((e) => occursOn(e, selectedDate)).sort((a, b) => a.start - b.start),
    [approvedEvents, selectedDate],
  )

  // 주 뷰는 선택 날짜가 속한 주, 일 뷰는 선택 날짜 하루만 본다.
  const agendaDays = useMemo(() => {
    if (viewMode === 'day') return [selectedDate]
    return eachDayOfInterval({
      start: startOfWeek(selectedDate, { weekStartsOn: 0 }),
      end: endOfWeek(selectedDate, { weekStartsOn: 0 }),
    })
  }, [viewMode, selectedDate])

  // 월 이동 시 선택 날짜도 새 달의 같은 일(길이 초과 시 말일로 보정)로 옮긴다.
  const moveMonth = (delta) => {
    const next = addMonths(month, delta)
    const day = Math.min(getDate(selectedDate), getDaysInMonth(next))
    setMonth(next)
    setSelectedDate(setDate(next, day))
  }

  // 일정 추가 모달을 선택 날짜로 열어 준다.
  const openCreate = () => {
    setEditing(null)
    setFormOpen(true)
  }

  const tabs = [
    { value: ALL_TAB, label: '전체' },
    ...departments.map((d) => ({ value: d.departmentId, label: d.name })),
  ]

  const draftColumns = [
    {
      key: 'title',
      header: '제목',
      render: (row) => (
        <div className="flex items-center gap-2">
          <span
            className={cn(
              'size-2 shrink-0 rounded-full',
              ACCENT[row.visibilityType] ?? 'bg-slate-400',
            )}
          />
          <span className="font-medium text-slate-800">{row.title}</span>
        </div>
      ),
    },
    {
      key: 'source',
      header: '출처 문서',
      render: (row) => {
        const fileName = sourceFileName(row)
        if (fileName) {
          return (
            <span className="flex items-center gap-1.5 text-slate-600">
              <FileText className="size-4 shrink-0 text-primary-500" />
              {fileName}
            </span>
          )
        }
        return (
          <span className="flex items-center gap-1.5 text-slate-400">
            <FileText className="size-4 shrink-0 text-slate-300" />
            {row.sourceGroupKey ? 'AI 추출 문서' : '—'}
          </span>
        )
      },
    },
    {
      key: 'location',
      header: '장소',
      render: (row) => (
        <span className={cn('flex items-center gap-1.5', row.location ? 'text-slate-600' : 'text-slate-400')}>
          <MapPin className="size-4 shrink-0 text-slate-300" />
          {row.location || '미정'}
        </span>
      ),
    },
    {
      key: 'time',
      header: '시간',
      render: (row) => (
        <div>
          <p className="font-medium text-slate-800">
            {format(row.start, 'yyyy.MM.dd (EEE)', { locale: ko })}
          </p>
          <p className="text-xs text-slate-400">
            {format(row.start, 'HH:mm')} ~ {format(row.end, 'HH:mm')}
          </p>
        </div>
      ),
    },
    {
      key: 'actions',
      header: '관리',
      align: 'right',
      headerAlign: 'right',
      render: (row) => (
        <Button size="sm" variant="outline" onClick={() => setDraftDetail(row)}>
          상세 보기
        </Button>
      ),
    },
  ]

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
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

        {/* 월/주/일 뷰 전환 */}
        <div className="flex items-center gap-0.5 rounded-lg border border-slate-200 bg-white p-0.5">
          {VIEW_MODES.map((mode) => (
            <button
              key={mode.value}
              type="button"
              onClick={() => setViewMode(mode.value)}
              aria-pressed={viewMode === mode.value}
              className={cn(
                'focus-ring rounded-md px-4 py-1.5 text-sm font-medium',
                viewMode === mode.value
                  ? 'bg-primary-600 text-white'
                  : 'text-slate-500 hover:bg-slate-50',
              )}
            >
              {mode.label}
            </button>
          ))}
        </div>
      </div>

      <Tabs items={tabs} value={deptTab} onChange={setDeptTab} />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_20rem]">
        {approvedQuery.isError ? (
          <Card>
            <div className="flex flex-col items-center gap-3 px-6 py-16 text-center">
              <span className="flex size-12 items-center justify-center rounded-full bg-rose-50 text-rose-500">
                <AlertTriangle className="size-6" />
              </span>
              <p className="text-sm text-slate-600">일정을 불러오지 못했어요</p>
              <Button variant="outline" onClick={() => approvedQuery.refetch()} loading={approvedQuery.isFetching}>
                <RotateCw className="size-4" />
                다시 시도
              </Button>
            </div>
          </Card>
        ) : viewMode === 'month' ? (
          <CalendarGrid
            monthDate={month}
            events={approvedEvents}
            selectedDate={selectedDate}
            onSelectDate={setSelectedDate}
            onEventClick={(event) => {
              setEditing(event)
              setFormOpen(true)
            }}
          />
        ) : (
          <AgendaView
            days={agendaDays}
            events={approvedEvents}
            selectedDate={selectedDate}
            onSelectDate={setSelectedDate}
            onEventClick={(event) => {
              setEditing(event)
              setFormOpen(true)
            }}
          />
        )}

        {/* 선택 날짜 상세 */}
        <Card className="flex h-full flex-col">
          <div className="flex items-start justify-between gap-2 border-b border-slate-100 px-4 py-3">
            <div>
              <h2 className="text-base font-bold text-slate-800">
                {format(selectedDate, 'M월 d일', { locale: ko })}
              </h2>
              <p className="mt-0.5 text-xs text-slate-400">
                {format(selectedDate, 'EEEE', { locale: ko })}
                {isToday(selectedDate) && ' · 오늘'}
              </p>
            </div>
            <Badge tone="primary">일정 {daySchedules.length}건</Badge>
          </div>
          <div className="flex-1 space-y-2 p-3">
            {approvedQuery.isLoading ? (
              <p className="py-6 text-center text-sm text-slate-400">불러오는 중…</p>
            ) : daySchedules.length === 0 ? (
              <EmptyState
                title="일정이 없어요"
                description="이 날에는 승인된 일정이 없습니다."
                className="py-8"
              />
            ) : (
              daySchedules.map((e) => (
                <DayScheduleCard
                  key={e.id}
                  event={e}
                  onClick={(event) => {
                    setEditing(event)
                    setFormOpen(true)
                  }}
                />
              ))
            )}
          </div>
          <div className="p-3">
            <button
              type="button"
              onClick={openCreate}
              className="focus-ring flex w-full items-center justify-center gap-1.5 rounded-lg border border-dashed border-primary-300 bg-primary-50/40 px-3 py-2.5 text-sm font-medium text-primary-700 hover:bg-primary-50"
            >
              <Plus className="size-4" />
              이 날짜에 일정 추가
            </button>
          </div>
        </Card>
      </div>

      {/* 승인 대기 일정 목록 */}
      <Card className="overflow-hidden">
        <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-base font-bold text-slate-800">승인 대기 일정 목록</h2>
              {draftEvents.length > 0 && <Badge tone="warning">{draftEvents.length}건</Badge>}
            </div>
            <p className="mt-0.5 text-sm text-slate-400">
              AI가 문서에서 추출한 일정입니다. 승인해야 캘린더에 반영됩니다
            </p>
          </div>
          <Button onClick={openCreate}>
            <Plus className="size-4" />
            일정 추가
          </Button>
        </div>
        {draftQuery.isError ? (
          <div className="flex flex-col items-center gap-2 border-t border-slate-100 py-12 text-center">
            <p className="text-sm text-slate-500">초안을 불러오지 못했어요</p>
            <Button
              variant="outline"
              size="sm"
              onClick={() => draftQuery.refetch()}
              loading={draftQuery.isFetching}
            >
              <RotateCw className="size-4" />
              다시 시도
            </Button>
          </div>
        ) : (
          <DataTable
            className="rounded-none border-0 border-t border-slate-100 shadow-none"
            columns={draftColumns}
            rows={draftEvents}
            rowKey="id"
            loading={draftQuery.isLoading}
            emptyState={<EmptyState title="검수할 초안이 없어요" />}
          />
        )}
      </Card>

      <AdminScheduleFormModal
        open={formOpen}
        onClose={() => setFormOpen(false)}
        initial={editing}
        defaultDate={selectedDate}
      />
      <ScheduleDraftDetailModal
        open={Boolean(draftDetail)}
        onClose={() => setDraftDetail(null)}
        draft={draftDetail}
        departments={departments}
      />
    </div>
  )
}
