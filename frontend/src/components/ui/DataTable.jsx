import { cn } from '@/shared/lib/cn'
import Spinner from './Spinner'
import EmptyState from './EmptyState'

// 설정형 테이블.
// columns: [{ key, header, render?(row), align?, className?, headerClassName? }]
// rows: 데이터 배열, rowKey: 행 고유키(문자열 키 또는 (row)=>key)
// toolbar: 상단 도구영역(검색/필터/버튼), onRowClick: 행 클릭 핸들러(있으면 hover 커서)
export default function DataTable({
  columns,
  rows,
  rowKey = 'id',
  loading = false,
  emptyState,
  onRowClick,
  toolbar,
  className,
}) {
  const keyOf = (row, i) => (typeof rowKey === 'function' ? rowKey(row) : (row[rowKey] ?? i))

  return (
    <div className={cn('overflow-hidden rounded-xl border border-slate-200 bg-white', className)}>
      {toolbar && (
        <div className="flex flex-wrap items-center gap-3 border-b border-slate-100 px-4 py-3">
          {toolbar}
        </div>
      )}
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-100 bg-slate-50 text-xs font-medium text-slate-500">
              {columns.map((col) => (
                <th
                  key={col.key}
                  className={cn(
                    'whitespace-nowrap px-4 py-3 text-center',
                    col.headerClassName,
                  )}
                >
                  {col.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-50">
            {loading ? (
              <tr>
                <td colSpan={columns.length} className="py-12">
                  <div className="flex justify-center">
                    <Spinner />
                  </div>
                </td>
              </tr>
            ) : rows.length === 0 ? (
              <tr>
                <td colSpan={columns.length} className="p-4">
                  {emptyState ?? <EmptyState />}
                </td>
              </tr>
            ) : (
              rows.map((row, i) => (
                <tr
                  key={keyOf(row, i)}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                  className={cn(
                    'text-slate-700',
                    onRowClick && 'cursor-pointer hover:bg-slate-50',
                  )}
                >
                  {columns.map((col, columnIndex) => {
                    const content = col.render ? col.render(row) : row[col.key]
                    return (
                    <td
                      key={col.key}
                      className={cn(
                        'px-4 py-3',
                        columnIndex === 0 ? 'text-left' : 'text-center',
                        col.className,
                      )}
                    >
                      {columnIndex === 0 ? (
                        content
                      ) : (
                        <div className="flex w-full items-center justify-center text-center">
                          {content}
                        </div>
                      )}
                    </td>
                    )
                  })}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
