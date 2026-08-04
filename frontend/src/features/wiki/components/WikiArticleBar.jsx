import { useMemo, useRef } from 'react'
import { ChevronDown, ListOrdered, PanelLeft, Sparkles } from 'lucide-react'
import { useWiki } from '../queries'
import { extractHeadings, numberHeadings } from '../headings'
import WikiTocList from './WikiTocList'

// 본문 위에 붙는 얇은 제어 바. 스크롤해도 남는다(`sticky`).
//
// 왜 한 줄로 모았나: 예전에는 좁은 화면에서 「목차·근거」 칩이 본문 카드 밖 빈 여백에 혼자
// 떠 있어 붙여넣은 것처럼 보였다. 지금은 이 화면에서 본문 말고 무엇을 열 수 있는지가
// 본문 카드 안 한 줄에 모여 있고, 열이 이미 보이는 것은 버튼이 뜨지 않는다.
//
// 목차는 팝오버다 — 본문에서 파생된 내용이라 항상 자리를 차지할 필요는 없지만, 문서에
// 목차가 있다는 사실 자체는 늘 보여야 한다(그래서 개수를 같이 적는다).
//
// `useWiki` 는 본문이 이미 부르는 쿼리라 같은 캐시를 읽는다(추가 요청 없음).
// 어느 폭에서 버튼이 보이는지는 **CSS 가 정한다**(`treeButtonClassName` 등에 `lg:hidden`).
// 열이 이미 서 있으면 그 열을 여는 버튼은 군더더기라 숨긴다. 자바스크립트로 폭을 재면
// 브레이크포인트가 CSS 와 두 군데로 갈라져 서로 어긋난다.
export default function WikiArticleBar({
  wikiId,
  onOpenTree,
  onOpenChat,
  treeButtonClassName = '',
  chatButtonClassName = '',
}) {
  const tocRef = useRef(null)
  const { data: wiki } = useWiki(wikiId)
  const headings = useMemo(
    () => numberHeadings(extractHeadings(wiki?.contentMarkdown)),
    [wiki?.contentMarkdown],
  )

  if (!onOpenTree && !onOpenChat && !headings.length) return null

  return (
    <div className="sticky top-0 z-20 -mx-5 mb-4 flex items-center gap-2 border-b border-slate-200 bg-white/95 px-5 pb-3 pt-4 backdrop-blur sm:-mx-6 sm:px-6">
      {onOpenTree && (
        <BarButton
          icon={PanelLeft}
          label="문서 목록"
          onClick={onOpenTree}
          className={treeButtonClassName}
        />
      )}

      {headings.length > 0 && (
        <details ref={tocRef} className="group relative">
          <summary className="focus-ring flex cursor-pointer list-none items-center gap-1.5 rounded-xl border border-slate-200 px-3 py-1.5 text-xs font-semibold text-slate-600 transition-colors hover:bg-slate-50 hover:text-primary-600">
            <ListOrdered className="size-4 text-slate-400" />
            목차
            <span className="font-normal text-slate-400">{headings.length}</span>
            <ChevronDown className="size-3.5 text-slate-400 transition-transform group-open:rotate-180" />
          </summary>
          <div className="absolute left-0 top-full z-30 mt-1.5 max-h-[60vh] w-72 overflow-y-auto rounded-2xl border border-slate-200 bg-white p-2 shadow-xl">
            <WikiTocList
              headings={headings}
              onNavigate={() => {
                if (tocRef.current) tocRef.current.open = false
              }}
            />
          </div>
        </details>
      )}

      {onOpenChat && (
        <BarButton
          icon={Sparkles}
          label="AI 문서 편집"
          onClick={onOpenChat}
          className={`ml-auto ${chatButtonClassName}`}
        />
      )}
    </div>
  )
}

function BarButton({ icon: Icon, label, onClick, className = '' }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`focus-ring inline-flex items-center gap-1.5 rounded-xl border border-slate-200 px-3 py-1.5 text-xs font-semibold text-slate-600 transition-colors hover:bg-slate-50 hover:text-primary-600 ${className}`}
    >
      <Icon className="size-4 text-slate-400" />
      {label}
    </button>
  )
}
