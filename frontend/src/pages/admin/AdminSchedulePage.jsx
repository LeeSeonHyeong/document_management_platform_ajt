import { useMemo, useState } from 'react'
import {
  startOfMonth,
  endOfMonth,
  startOfWeek,
  endOfWeek,
  addMonths,
  format,
} from 'date-fns'
import { ko } from 'date-fns/locale'
import { ChevronLeft, ChevronRight, Plus, MapPin, Clock, AlertTriangle, RotateCw } from 'lucide-react'
import CalendarGrid from '@/components/calendar/CalendarGrid'
import AdminScheduleFormModal from '@/components/calendar/AdminScheduleFormModal'
import ScheduleDraftDetailModal from '@/components/calendar/ScheduleDraftDetailModal'
import { Button, Card, EmptyState, Badge, Tabs } from '@/components/ui'
import { useSchedules } from '@/features/schedule/useSchedules'
import { useDepartments } from '@/features/department/useDepartments'
import { filterByDepartmentTab } from '@/features/schedule/adminFilters'
import { SCHEDULE_VISIBILITY, SCHEDULE_VISIBILITY_LABELS } from '@/shared/constants/enums'

const ALL_TAB = 'all'

// 5R 관리자 일정 관리·초안 검수. 승인된 일정은 달력에, 초안은 승인 대기 목록에 표시한다.
export default function AdminSchedulePage() {
  const [month, setMonth] = useState(() => startOfMonth(new Date()))
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
      filterByDepartmentTab(draftQuery.data ?? [], deptId).sort((a, b) => a.start - b.start),
    [draftQuery.data, deptId],
  )

  const moveMonth = (delta) => setMonth((m) => addMonths(m, delta))

  const tabs = [
    { value: ALL_TAB, label: '전체' },
    ...departments.map((d) => ({ value: d.departmentId, label: d.name })),
  ]

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
              onClick={() => setMonth(startOfMonth(new Date()))}
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
        <Button
          onClick={() => {
            setEditing(null)
            setFormOpen(true)
          }}
        >
          <Plus className="size-4" />
          일정 등록
        </Button>
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
        ) : (
          <CalendarGrid
            monthDate={month}
            events={approvedEvents}
            onEventClick={(event) => {
              setEditing(event)
              setFormOpen(true)
            }}
          />
        )}

        <Card>
          <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
            <h2 className="text-sm font-semibold text-slate-800">승인 대기 일정</h2>
            {draftEvents.length > 0 && <Badge tone="warning">{draftEvents.length}</Badge>}
          </div>
          <div className="space-y-2 p-3">
            <p className="px-1 pb-1 text-xs text-slate-400">
              AI가 문서에서 추출한 초안입니다. 승인해야 사원 달력에 표시됩니다.
            </p>
            {draftQuery.isError ? (
              <div className="flex flex-col items-center gap-2 py-8 text-center">
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
            ) : draftQuery.isLoading ? (
              <p className="py-6 text-center text-sm text-slate-400">불러오는 중…</p>
            ) : draftEvents.length === 0 ? (
              <EmptyState title="검수할 초안이 없어요" className="py-8" />
            ) : (
              draftEvents.map((e) => (
                <button
                  key={e.id}
                  type="button"
                  onClick={() => setDraftDetail(e)}
                  className="focus-ring flex w-full flex-col gap-1 rounded-lg border border-amber-100 bg-amber-50/40 px-3 py-2.5 text-left hover:bg-amber-50"
                >
                  <div className="flex items-center gap-2">
                    <p className="truncate text-sm font-medium text-slate-800">{e.title}</p>
                    <Badge tone={e.visibilityType === SCHEDULE_VISIBILITY.ALL ? 'primary' : 'info'}>
                      {SCHEDULE_VISIBILITY_LABELS[e.visibilityType]}
                    </Badge>
                  </div>
                  <p className="flex items-center gap-1 text-xs text-slate-500">
                    <Clock className="size-3" />
                    {format(e.start, 'M.d (EEE) HH:mm', { locale: ko })}
                  </p>
                  {e.location && (
                    <p className="flex items-center gap-1 text-xs text-slate-400">
                      <MapPin className="size-3" />
                      {e.location}
                    </p>
                  )}
                </button>
              ))
            )}
          </div>
        </Card>
      </div>

      <AdminScheduleFormModal
        open={formOpen}
        onClose={() => setFormOpen(false)}
        initial={editing}
        defaultDate={month}
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
