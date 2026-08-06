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
  tableClassName,
  // 스크롤 영역에 붙일 클래스. 높이를 제한해 표 안에서만 스크롤하게 할 때 쓴다
  // (예: 'max-h-[28rem]'). overflow-x-auto 는 세로도 함께 auto 가 되므로 이 div 가
  // 스크롤 컨테이너다 — 바깥에 높이를 걸면 헤더까지 같이 밀려 올라간다.
  scrollClassName,
  // 헤더를 스크롤 위에 고정한다. 높이를 제한할 때 함께 쓴다.
  stickyHeader = false,
}) {
  const keyOf = (row, i) => (typeof rowKey === 'function' ? rowKey(row) : (row[rowKey] ?? i))

  return (
    <div className={cn('overflow-hidden rounded-xl border border-slate-200 bg-white', className)}>
      {toolbar && (
        <div className="flex flex-wrap items-center gap-3 border-b border-slate-100 px-4 py-3">
          {toolbar}
        </div>
      )}
      <div className={cn('overflow-x-auto', scrollClassName)}>
        <table className={cn('w-full text-sm', tableClassName)}>
          <thead>
            <tr className="border-b border-slate-100 bg-slate-50 text-xs font-medium text-slate-500">
              {columns.map((col) => (
                <th
                  key={col.key}
                  className={cn(
                    'whitespace-nowrap px-4 py-3 text-center',
                    // tr 의 배경은 sticky 로 떠오른 th 를 덮어주지 못한다 — th 에 직접 준다.
                    stickyHeader && 'sticky top-0 z-10 bg-slate-50',
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
                        // 좁은 화면에서 뱃지·버튼·날짜 등이 세로로 찌그러지지 않게 줄바꿈을 막는다.
                        // 표 자연 너비가 컨테이너를 넘으면 overflow-x-auto가 가로 스크롤을 만든다.
                        // 첫 열은 이름+부가정보 같은 2줄 렌더가 있어 줄바꿈을 허용한다.
                        columnIndex === 0 ? 'text-left' : 'whitespace-nowrap text-center',
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
