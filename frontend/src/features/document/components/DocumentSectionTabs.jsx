import { NavLink } from 'react-router-dom'
import { cn } from '@/shared/lib/cn'
import { useDocuments } from '../queries'

const TABS = [
  { to: '/admin/documents', label: '업로드', end: true },
  { to: '/admin/documents/source', label: '원본 문서' },
  { to: '/admin/documents/summaries', label: '요약 목록' },
]

export default function DocumentSectionTabs() {
  const { data: sourceData } = useDocuments({ page: 1, size: 1 })
  const { data: completedData } = useDocuments({ page: 1, size: 1, status: 'completed' })
  const sourceCount = sourceData?.totalCount ?? sourceData?.totalItems ?? sourceData?.items?.length
  // 전체 AI 작업 이력 API가 생기기 전까지 완료 문서가 있으면 요약 묶음 1건으로 표시한다.
  const completedCount =
    completedData?.totalCount ?? completedData?.totalItems ?? completedData?.items?.length ?? 0
  const summaryCount = completedCount > 0 ? 1 : 0
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
    </div>
  )
}
