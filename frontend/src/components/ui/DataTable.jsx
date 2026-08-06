import { cn } from '@/shared/lib/cn'
import Spinner from './Spinner'
import EmptyState from './EmptyState'

// 설정형 테이블.
// columns: [{ key, header, render?(row), align?, className?, headerClassName?, width? }]
//
// width('20%' 같은 CSS 값)를 하나라도 주면 `table-fixed` + <colgroup> 으로 열 너비를 **고정**한다.
// 기본값(table-auto)은 지금 보이는 행의 내용으로 너비를 정하기 때문에, 상단 카드로 필터를
// 바꿀 때마다(전체 직원 → 관리자) 같은 표인데 열 위치가 통째로 밀린다 — 화면이 바뀐 것처럼 보인다.
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
  const fixedLayout = columns.some((col) => col.width)

  return (
    <div className={cn('overflow-hidden rounded-xl border border-slate-200 bg-white', className)}>
      {toolbar && (
        <div className="flex flex-wrap items-center gap-3 border-b border-slate-100 px-4 py-3">
          {toolbar}
        </div>
      )}
      <div className={cn('overflow-x-auto', scrollClassName)}>
        <table className={cn('w-full text-sm', fixedLayout && 'table-fixed', tableClassName)}>
          {fixedLayout && (
            <colgroup>
              {columns.map((col) => (
                <col key={col.key} style={col.width ? { width: col.width } : undefined} />
              ))}
            </colgroup>
          )}
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
                    // 문자열 값(이메일·부서·사번…)은 너비를 고정한 표에서 줄바꿈 대신 '…'로 줄인다.
                    // 세 줄로 늘어난 이름 한 칸 때문에 행 전체가 높아지던 문제(S15P11B106) 때문이다.
                    // 전체 값은 title 로 남겨 마우스를 올리면 볼 수 있다.
                    const plainText = typeof content === 'string' || typeof content === 'number'
                    const clamp = fixedLayout && plainText
                    return (
                    <td
                      key={col.key}
                      title={clamp ? String(content) : undefined}
                      className={cn(
                        'px-4 py-3',
                        // 좁은 화면에서 뱃지·버튼·날짜 등이 세로로 찌그러지지 않게 줄바꿈을 막는다.
                        // 표 자연 너비가 컨테이너를 넘으면 overflow-x-auto가 가로 스크롤을 만든다.
                        // 첫 열은 이름+부가정보 같은 2줄 렌더가 있어 줄바꿈을 허용한다.
                        columnIndex === 0 ? 'text-left' : 'whitespace-nowrap text-center',
                        // 너비를 고정한 표에서는 유난히 긴 값이 옆 열을 밀지 않고 잘린다.
                        fixedLayout && 'overflow-hidden',
                        clamp && 'truncate',
                        col.className,
                      )}
                    >
                      {columnIndex === 0 || clamp ? (
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
