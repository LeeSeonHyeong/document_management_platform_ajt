import { NavLink } from 'react-router-dom'
import { Settings } from 'lucide-react'
import { cn } from '@/shared/lib/cn'
import { useAiJobs, useDocuments } from '../queries'
import { readPreviewSourceDocuments } from '../previewStorage'
import { DOCUMENT_STATUS } from '@/shared/constants/enums'

const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])

const TABS = [
  { to: '/admin/documents', label: '업로드', end: true },
  { to: '/admin/documents/source', label: '원본 문서' },
  { to: '/admin/documents/summaries', label: '요약 목록' },
]

export default function DocumentSectionTabs() {
  // 원본 문서 탭의 수는 그 화면이 실제로 보여주는 것과 같아야 한다(S15P11B106-300).
  // 목록은 위키 반영이 끝난 문서만 보여주는데(S15P11B106-288) 탭은 전체를 세서, 실패·처리 중
  // 문서까지 포함된 「13」과 목록의 「9건」이 어긋났다.
  const { data: sourceData } = useDocuments({
    page: 1,
    size: 1,
    status: DOCUMENT_STATUS.COMPLETED,
  })
  // 목록은 삭제·교체가 실패한 문서도 되살려 보여준다 — 위키 근거로는 남아 있기 때문이다
  // (S15P11B106-306). 탭도 같은 기준으로 세야 한다(S15P11B106-317).
  const { data: failedData } = useDocuments({
    page: 1,
    size: 100,
    status: DOCUMENT_STATUS.FAILED,
  })
  const failedEvidenceCount = (failedData?.items ?? []).filter(
    (document) => (document.wikiCount ?? 0) > 0,
  ).length
  const { data: jobsData } = useAiJobs({ page: 1, size: 20 })
  // 세션스토리지에 남은 옛 미리보기 항목에도 같은 조건을 적용한다(서버 필터를 타지 않는다).
  const previewSourceCount = readPreviewSourceDocuments().filter(
    (document) => document.status === DOCUMENT_STATUS.COMPLETED,
  ).length
  const serverSourceCount =
    sourceData?.totalCount ?? sourceData?.totalItems ?? sourceData?.totalElements ?? sourceData?.items?.length
  const sourceCount =
    serverSourceCount == null
      ? previewSourceCount + failedEvidenceCount
      : serverSourceCount + previewSourceCount + failedEvidenceCount
  // 요약 목록이 보여주는 것과 같은 수 — 종료된 작업 회차의 개수다.
  const summaryCount = (jobsData?.items ?? []).filter((job) => TERMINAL_STATUSES.has(job.status)).length
  return (
    <div className="flex h-12 items-end gap-2 border-b border-slate-200">
      {TABS.map((tab) => (
        <NavLink
          key={tab.to}
          to={tab.to}
          end={tab.end}
          className={({ isActive }) =>
            cn(
              'focus-ring flex h-12 items-center gap-2 border-b-2 px-4 text-sm font-semibold transition-colors',
              isActive
                ? 'border-primary-600 text-primary-600'
                : 'border-transparent text-slate-400 hover:text-slate-600',
            )
          }
        >
          {tab.label}
          {tab.label === '원본 문서' && sourceCount != null && (
            <span className="text-xs text-primary-500">{sourceCount}</span>
          )}
          {tab.label === '요약 목록' && summaryCount != null && (
            <span className="text-xs text-primary-500">{summaryCount}</span>
          )}
        </NavLink>
      ))}
      <NavLink
        to="/admin/documents/categories"
        className={({ isActive }) =>
          cn(
            'focus-ring mb-2 ml-auto inline-flex h-8 items-center gap-1.5 rounded-lg border px-3 text-xs font-semibold transition-colors',
            isActive
              ? 'border-primary-300 bg-primary-50 text-primary-600'
              : 'border-slate-200 bg-white text-slate-500 hover:border-primary-200 hover:text-primary-600',
          )
        }
      >
        <Settings className="size-3.5" />
        카테고리 관리
      </NavLink>
    </div>
  )
}
