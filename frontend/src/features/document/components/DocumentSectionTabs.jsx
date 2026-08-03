import { NavLink } from 'react-router-dom'
import { Settings } from 'lucide-react'
import { cn } from '@/shared/lib/cn'
import { useAiJobs, useDocuments } from '../queries'
import { readPreviewSourceDocuments } from '../previewStorage'

const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])

const TABS = [
  { to: '/admin/documents', label: '업로드', end: true },
  { to: '/admin/documents/source', label: '원본 문서' },
  { to: '/admin/documents/summaries', label: '요약 목록' },
]

export default function DocumentSectionTabs() {
  const { data: sourceData } = useDocuments({ page: 1, size: 1 })
  const { data: jobsData } = useAiJobs({ page: 1, size: 20 })
  const previewSourceCount = readPreviewSourceDocuments().length
  const serverSourceCount =
    sourceData?.totalCount ?? sourceData?.totalItems ?? sourceData?.totalElements ?? sourceData?.items?.length
  const sourceCount = serverSourceCount == null ? previewSourceCount : serverSourceCount + previewSourceCount
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
