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
  addDays,
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
import { Button, Card, EmptyState, Badge, Spinner, Tabs, DataTable } from '@/components/ui'
import { cn } from '@/shared/lib/cn'
import { useSchedules } from '@/features/schedule/useSchedules'
import { useAiJobQueue } from '@/features/document/useAiJobQueue'
import { useDepartments } from '@/features/department/useDepartments'
import { filterByDepartmentTab } from '@/features/schedule/adminFilters'
import { SCHEDULE_VISIBILITY } from '@/shared/constants/enums'

const ALL_TAB = 'all'

// 월/주 뷰 전환. 월은 캘린더 그리드, 주는 요일×시간 그리드로 일정을 시간 위치대로 배치한다.
const VIEW_MODES = [
  { value: 'month', label: '월' },
  { value: 'week', label: '주' },
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

// 주 뷰 시간 그리드 표시 범위(09:00~18:00)와 1시간당 높이(px).
const WEEK_START_HOUR = 9
const WEEK_END_HOUR = 18
const HOUR_PX = 56

// 공개 범위별 일정 블록 색(좌측 강조선 + 옅은 배경).
const WEEK_BLOCK = {
  [SCHEDULE_VISIBILITY.ALL]: 'border-primary-500 bg-primary-50',
  [SCHEDULE_VISIBILITY.DEPARTMENT]: 'border-sky-500 bg-sky-50',
  [SCHEDULE_VISIBILITY.PERSONAL]: 'border-emerald-500 bg-emerald-50',
}

const WEEK_BLOCK_TEXT = {
  [SCHEDULE_VISIBILITY.ALL]: 'text-primary-900',
  [SCHEDULE_VISIBILITY.DEPARTMENT]: 'text-sky-900',
  [SCHEDULE_VISIBILITY.PERSONAL]: 'text-emerald-900',
}

// 이벤트가 해당 날짜 열에서 차지하는 상단 위치(px)와 높이(px). 표시 범위 밖이면 null.
function blockGeometry(event, date) {
  const dayStart = startOfDay(date).getTime()
  const dayEnd = endOfDay(date).getTime()
  const from = Math.max(event.start.getTime(), dayStart)
  const to = Math.min(event.end.getTime(), dayEnd)
  const gridTop = WEEK_START_HOUR * 60
  const gridBottom = WEEK_END_HOUR * 60
  const startMin = Math.max((from - dayStart) / 60000, gridTop)
  const endMin = Math.min((to - dayStart) / 60000, gridBottom)
  if (endMin <= gridTop || startMin >= gridBottom) return null
  return {
    top: ((startMin - gridTop) / 60) * HOUR_PX,
    height: Math.max(((endMin - startMin) / 60) * HOUR_PX, 22),
  }
}

// 하루치 일정을 시간 겹침에 따라 열로 나눈다.
// 서로(간접적으로) 겹치는 일정들을 한 묶음으로 보고, 묶음 안에서 열을 최소 개수로 배정해
// 각 블록에 열 인덱스(col)와 묶음의 총 열 수(colCount)를 매긴다. 겹치면 좌우로 나란히 표시된다.
function layoutDayEvents(events, date) {
  const items = events
    .map((event) => {
      const geo = blockGeometry(event, date)
      return geo ? { event, top: geo.top, height: geo.height, bottom: geo.top + geo.height } : null
    })
    .filter(Boolean)
    .sort((a, b) => a.top - b.top || a.bottom - b.bottom)

  const positioned = []
  let cluster = []
  let clusterEnd = -Infinity

  const flush = () => {
    if (cluster.length === 0) return
    // 각 열이 마지막으로 채운 bottom을 들고, 겹치지 않는 첫 열에 배정한다.
    const columnEnds = []
    cluster.forEach((item) => {
      let col = columnEnds.findIndex((end) => item.top >= end)
      if (col === -1) {
        col = columnEnds.length
        columnEnds.push(item.bottom)
      } else {
        columnEnds[col] = item.bottom
      }
      item.col = col
    })
    cluster.forEach((item) => {
      item.colCount = columnEnds.length
      positioned.push(item)
    })
    cluster = []
    clusterEnd = -Infinity
  }

  items.forEach((item) => {
    // 현재 묶음의 어떤 일정과도 겹치지 않으면(시작이 묶음의 끝 이상) 묶음을 마감한다.
    if (item.top >= clusterEnd) flush()
    cluster.push(item)
    clusterEnd = Math.max(clusterEnd, item.bottom)
  })
  flush()

  return positioned
}

// 주 뷰. 요일 헤더 + 시간대 그리드에 일정을 시간 위치대로 블록으로 배치한다.
function WeekGrid({ weekDays, events, selectedDate, onSelectDate }) {
  const now = new Date()
  const nowKey = format(now, 'yyyy-MM-dd')
  const gridHeight = (WEEK_END_HOUR - WEEK_START_HOUR) * HOUR_PX
  const hourLines = Array.from({ length: WEEK_END_HOUR - WEEK_START_HOUR + 1 }, (_, i) => i)
  const nowMin = now.getHours() * 60 + now.getMinutes()
  const nowTop = ((nowMin - WEEK_START_HOUR * 60) / 60) * HOUR_PX
  const nowVisible = nowMin >= WEEK_START_HOUR * 60 && nowMin <= WEEK_END_HOUR * 60

  return (
    <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
      {/* 요일 헤더 */}
      <div className="flex border-b border-slate-100 px-3 py-2">
        <div className="w-14 shrink-0" />
        {weekDays.map((date) => {
          const selected = format(date, 'yyyy-MM-dd') === format(selectedDate, 'yyyy-MM-dd')
          const sunday = date.getDay() === 0
          return (
            <button
              key={date.toISOString()}
              type="button"
              onClick={() => onSelectDate(date)}
              className="focus-ring flex flex-1 flex-col items-center gap-1 rounded-lg py-1"
            >
              <span className={cn('text-xs', sunday ? 'text-rose-500' : 'text-slate-400')}>
                {format(date, 'EEE', { locale: ko })}
              </span>
              <span
                className={cn(
                  'flex size-7 items-center justify-center rounded-full text-sm font-semibold',
                  selected
                    ? 'bg-primary-600 text-white'
                    : sunday
                      ? 'text-rose-500'
                      : 'text-slate-700',
                )}
              >
                {format(date, 'd')}
              </span>
            </button>
          )
        })}
      </div>

      {/* 시간 그리드 */}
      <div className="flex px-3 py-3">
        {/* 시간 눈금 */}
        <div className="relative w-14 shrink-0" style={{ height: gridHeight }}>
          {hourLines.map((i) => (
            <span
              key={i}
              className="absolute right-2 -translate-y-1/2 text-xs text-slate-400"
              style={{ top: i * HOUR_PX }}
            >
              {String(WEEK_START_HOUR + i).padStart(2, '0')}:00
            </span>
          ))}
        </div>

        {/* 날짜별 열 */}
        {weekDays.map((date) => {
          const selected = format(date, 'yyyy-MM-dd') === format(selectedDate, 'yyyy-MM-dd')
          const isNowColumn = format(date, 'yyyy-MM-dd') === nowKey
          const dayEvents = events.filter((e) => occursOn(e, date))
          return (
            <div
              key={date.toISOString()}
              role="button"
              tabIndex={0}
              onClick={() => onSelectDate(date)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault()
                  onSelectDate(date)
                }
              }}
              className={cn(
                'focus-ring relative flex-1 cursor-pointer border-l border-slate-100',
                selected && 'bg-primary-50/40',
              )}
              style={{ height: gridHeight }}
            >
              {/* 시간별 가로줄 */}
              {hourLines.map((i) => (
                <div
                  key={i}
                  className="pointer-events-none absolute inset-x-0 border-t border-slate-100"
                  style={{ top: i * HOUR_PX }}
                />
              ))}

              {/* 현재 시각 표시 */}
              {nowVisible && isNowColumn && (
                <div className="pointer-events-none absolute inset-x-0" style={{ top: nowTop }}>
                  <div className="absolute inset-x-0 z-20 border-t-2 border-rose-500" />
                  <span className="absolute -left-9 top-0 z-40 flex h-5 w-10 -translate-y-1/2 items-center justify-center rounded-full bg-rose-500 text-[10px] font-semibold text-white">
                    현재
                  </span>
                </div>
              )}

              {/* 일정 블록. 겹치는 일정은 좌우 열로 나눠 나란히 표시한다. */}
              {layoutDayEvents(dayEvents, date).map(({ event, top, height, col, colCount }) => {
                return (
                  <div
                    key={event.id}
                    style={{
                      top,
                      height,
                      left: `calc(${(col / colCount) * 100}% + 2px)`,
                      width: `calc(${100 / colCount}% - 4px)`,
                    }}
                    className="pointer-events-none absolute"
                  >
                    <div
                      className={cn(
                        'absolute inset-0 z-10 rounded-md border-l-4',
                        WEEK_BLOCK[event.visibilityType] ?? 'border-slate-400 bg-slate-50',
                      )}
                    />
                    <div
                      className={cn(
                        'relative z-30 h-full overflow-hidden px-2 py-1 text-left',
                        WEEK_BLOCK_TEXT[event.visibilityType] ?? 'text-slate-800',
                      )}
                    >
                      <p className="truncate text-xs font-semibold leading-tight">{event.title}</p>
                      <p className="truncate text-[11px] leading-tight opacity-80">
                        {format(event.start, 'HH:mm')} – {format(event.end, 'HH:mm')}
                      </p>
                    </div>
                  </div>
                )
              })}
            </div>
          )
        })}
      </div>
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
  // 문서 관리에서 시작한 일정 추출이 아직 돌고 있으면 여기서도 알려준다(S15P11B106-287).
  // 셸이 들고 있는 값이라 화면을 옮겨도 유지된다 — 추출은 브라우저가 붙잡은 요청이라 서버에
  // 물어볼 진행 상태가 없다.
  const { extractingSchedules } = useAiJobQueue()
  const approvedQuery = useSchedules({ ...range, status: 'approved' })
  // 승인 대기 목록은 달 범위를 걸지 않는다.
  //
  // 검수할 초안이 어느 달에 있는지 관리자가 미리 알 수 없다. 문서에서 추출된 일정은 과거·미래
  // 어디로든 흩어지므로, 보고 있는 달로 걸면 「추출 완료」가 떴는데 목록이 비어 실패로 보이고
  // (S15P11B106-276 확인) 검수를 놓친 초안이 다른 달에 묻힌다. 캘린더만 달 단위로 둔다.
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

  // 주 뷰는 선택 날짜가 속한 한 주(일~토)를 본다.
  const weekDays = useMemo(
    () =>
      eachDayOfInterval({
        start: startOfWeek(selectedDate, { weekStartsOn: 0 }),
        end: endOfWeek(selectedDate, { weekStartsOn: 0 }),
      }),
    [selectedDate],
  )

  // 헤더 제목: 월 뷰는 "yyyy년 M월", 주 뷰는 선택 주의 기간("yyyy년 M월 d일 – d일").
  const periodLabel =
    viewMode === 'week'
      ? `${format(startOfWeek(selectedDate, { weekStartsOn: 0 }), 'yyyy년 M월 d일')} – ${format(endOfWeek(selectedDate, { weekStartsOn: 0 }), 'd일')}`
      : format(month, 'yyyy년 M월')

  // 월 이동 시 선택 날짜도 새 달의 같은 일(길이 초과 시 말일로 보정)로 옮긴다.
  const moveMonth = (delta) => {
    const next = addMonths(month, delta)
    const day = Math.min(getDate(selectedDate), getDaysInMonth(next))
    setMonth(next)
    setSelectedDate(setDate(next, day))
  }

  // 이전/다음 이동. 주 뷰는 주 단위로 옮기고, 조회 범위(month)도 새 주가 포함되도록 따라간다.
  const movePeriod = (delta) => {
    if (viewMode === 'week') {
      const next = addDays(selectedDate, delta * 7)
      setSelectedDate(next)
      setMonth(startOfMonth(next))
    } else {
      moveMonth(delta)
    }
  }

  // 일정 추가 모달을 선택 날짜로 열어 준다.
  const openCreate = () => {
    setEditing(null)
    setFormOpen(true)
  }

  const tabs = [
    // '전체 일정'이다 — '전체 부서'는 공개 범위의 '전체 공개'와 헷갈린다(S15P11B106-204).
    // 이 탭은 무엇을 볼지 고르는 필터이지 일정의 공개 범위가 아니다.
    { value: ALL_TAB, label: '전체 일정' },
    ...departments.map((d) => ({ value: d.departmentId, label: d.name })),
  ]

  // width 를 주면 표가 table-fixed 로 그려진다 — 부서 탭을 바꿔 행이 달라져도 열이 밀리지 않는다.
  // 제목·파일명·장소는 길이가 제각각이라 줄바꿈 대신 '…'로 줄이고 전체 값은 title 로 남긴다.
  const draftColumns = [
    {
      key: 'title',
      header: '제목',
      width: '28%',
      render: (row) => (
        <div className="flex min-w-0 items-center gap-2">
          <span
            className={cn(
              'size-2 shrink-0 rounded-full',
              ACCENT[row.visibilityType] ?? 'bg-slate-400',
            )}
          />
          <span className="truncate font-medium text-slate-800" title={row.title}>{row.title}</span>
        </div>
      ),
    },
    {
      key: 'source',
      header: '출처 문서',
      width: '26%',
      render: (row) => {
        const fileName = sourceFileName(row)
        if (fileName) {
          return (
            <span className="flex min-w-0 items-center gap-1.5 text-slate-600">
              <FileText className="size-4 shrink-0 text-primary-500" />
              <span className="truncate" title={fileName}>{fileName}</span>
            </span>
          )
        }
        return (
          <span className="flex min-w-0 items-center gap-1.5 text-slate-400">
            <FileText className="size-4 shrink-0 text-slate-300" />
            {row.sourceGroupKey ? 'AI 추출 문서' : '—'}
          </span>
        )
      },
    },
    {
      key: 'location',
      header: '장소',
      width: '18%',
      render: (row) => (
        <span className={cn('flex min-w-0 items-center gap-1.5', row.location ? 'text-slate-600' : 'text-slate-400')}>
          <MapPin className="size-4 shrink-0 text-slate-300" />
          <span className="truncate" title={row.location || '미정'}>{row.location || '미정'}</span>
        </span>
      ),
    },
    {
      key: 'time',
      header: '시간',
      width: '16%',
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
      width: '12%',
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
          <h1 className="text-xl font-bold text-slate-800">{periodLabel}</h1>
          <div className="flex items-center">
            <button
              type="button"
              onClick={() => movePeriod(-1)}
              className="focus-ring rounded-lg p-1.5 text-slate-500 hover:bg-slate-100"
              aria-label={viewMode === 'week' ? '이전 주' : '이전 달'}
            >
              <ChevronLeft className="size-5" />
            </button>
            <button
              type="button"
              onClick={() => movePeriod(1)}
              className="focus-ring rounded-lg p-1.5 text-slate-500 hover:bg-slate-100"
              aria-label={viewMode === 'week' ? '다음 주' : '다음 달'}
            >
              <ChevronRight className="size-5" />
            </button>
            <button
              type="button"
              onClick={() => {
                setMonth(startOfMonth(new Date()))
                setSelectedDate(new Date())
              }}
              className="focus-ring ml-1.5 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-sm text-slate-600 shadow-sm hover:bg-slate-50"
            >
              오늘
            </button>
          </div>
        </div>

        {/* 월/주 뷰 전환 */}
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

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(18rem,24rem)]">
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
          />
        ) : (
          <WeekGrid
            weekDays={weekDays}
            events={approvedEvents}
            selectedDate={selectedDate}
            onSelectDate={setSelectedDate}
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
                className="h-full rounded-none border-0 bg-transparent px-0 py-0"
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
            {extractingSchedules.length > 0 && (
              <div className="mt-2 flex items-center gap-2 rounded-lg bg-emerald-50 px-3 py-2 text-xs text-emerald-700">
                <Spinner size="sm" />
                <span>
                  일정 파일 {extractingSchedules.length}개에서 일정을 추출하고 있습니다. 끝나면 이
                  목록에 나타납니다
                </span>
              </div>
            )}
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
            // 초안은 모든 달을 모아 보여주므로 많아질 수 있다. 목록 안에서만 스크롤해
            // 아래 내용이 화면 밖으로 밀려나지 않게 한다. 헤더는 고정한다.
            scrollClassName="max-h-[28rem]"
            stickyHeader
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
