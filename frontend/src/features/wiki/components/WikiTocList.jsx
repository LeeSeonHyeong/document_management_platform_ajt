// 목차 목록. 지금은 본문 위 sticky 바의 팝오버(WikiArticleBar)가 유일한 사용처지만,
// 마크업과 스크롤 동작을 따로 두면 목차가 여러 자리에 놓일 때 번호·들여쓰기가 갈라진다.

// 목차 클릭 → 본문의 같은 id 헤딩으로 스크롤. 본문이 별도 스크롤 컨테이너일 수도 있어
// DOM id 로 찾아 scrollIntoView 한다. 헤딩이 sticky 바 뒤로 숨지 않게 하는 상단 여백은
// 본문 헤딩의 scroll-margin-top(= `--wiki-anchor-offset`, WikiArticleBar 가 실측해 넣는다)이 맡는다.
function scrollToHeading(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** `onNavigate` 는 목차를 담은 팝오버·서랍이 스스로 닫히기 위한 것이다(선택). */
export default function WikiTocList({ headings, onNavigate }) {
  if (!headings?.length) return <p className="text-xs text-slate-400">표시할 목차가 없습니다.</p>

  return (
    <ol className="space-y-1.5">
      {headings.map((heading, index) => (
        <li
          key={`${heading.id}-${index}`}
          style={{ marginLeft: `${(heading.level - 1) * 12}px` }}
        >
          <button
            type="button"
            onClick={() => {
              scrollToHeading(heading.id)
              onNavigate?.()
            }}
            className={`focus-ring block w-full rounded-lg px-3 py-2 text-left transition-colors hover:bg-primary-50 ${
              heading.level === 1
                ? 'text-xs font-semibold text-slate-700 hover:text-primary-600'
                : 'text-xs text-slate-500 hover:text-primary-600'
            }`}
          >
            {heading.number && (
              <span className="mr-1.5 font-semibold text-slate-400">{heading.number}</span>
            )}
            {heading.label}
          </button>
        </li>
      ))}
    </ol>
  )
}
