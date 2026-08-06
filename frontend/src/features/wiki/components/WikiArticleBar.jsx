import { useEffect, useMemo, useRef, useState } from 'react'
import { ChevronDown, Download, ListOrdered, PanelLeft, Sparkles } from 'lucide-react'
import { useToast } from '@/components/ui'
import { fetchWikiFile } from '../api'
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
  const toast = useToast()
  const tocRef = useRef(null)
  const barRef = useRef(null)
  const [downloading, setDownloading] = useState(false)
  const { data: wiki } = useWiki(wikiId)
  const headings = useMemo(
    () => numberHeadings(extractHeadings(wiki?.contentMarkdown)),
    [wiki?.contentMarkdown],
  )

  // 이 바는 sticky 라 본문 맨 위를 늘 덮고 있다. 목차에서 절을 고르면 그 헤딩이 **바 뒤에**
  // 멈춰서 제목은 안 보이고 본문 문단부터 보였다 — 어느 절로 왔는지 알 수 없었다.
  // 본문 헤딩(WikiMarkdown)이 쓰는 scroll-margin-top 을 바의 **실제 높이**로 넘긴다.
  // 상수로 박으면 바에 줄이 늘거나 여백이 바뀔 때 다시 어긋나므로 재서 쓴다.
  useEffect(() => {
    const bar = barRef.current
    if (!bar) return
    // +8px: 헤딩이 바 밑변에 딱 붙으면 잘린 것처럼 보인다.
    const apply = () =>
      document.documentElement.style.setProperty('--wiki-anchor-offset', `${bar.offsetHeight + 8}px`)
    apply()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(apply)
    observer?.observe(bar)
    return () => {
      observer?.disconnect()
      document.documentElement.style.removeProperty('--wiki-anchor-offset')
    }
  }, [wiki])

  // 다운로드는 위키가 있으면 항상 뜨는 기능이라, 이 바를 그릴지 말지는 이제 wiki 로딩
  // 여부로 정한다(예전엔 문서 목록·목차·AI 편집 중 하나라도 있어야 그렸다).
  if (!wiki) return null

  // 위키 본문 자체를 Markdown 파일로 내려받는다(GET /wikis/:wikiId/file).
  // 제목 옆 큰 버튼으로 뒀을 때는 <h1>과 시각적 무게를 다퉈 "붙여넣은" 느낌이 났다 —
  // 문서 목록·목차처럼 본문을 보조하는 기능이라 같은 바에 아이콘만으로 옮긴다.
  async function handleDownload() {
    setDownloading(true)
    try {
      const { blob, fileName } = await fetchWikiFile(wikiId)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = fileName ?? `${wiki?.title ?? 'wiki'}.md`
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      if (error?.status === 403) toast.error('다운로드 권한이 없습니다.')
      else toast.error('다운로드에 실패했습니다.')
    } finally {
      setDownloading(false)
    }
  }

  return (
    <div
      ref={barRef}
      className="sticky top-0 z-20 -mx-5 mb-4 flex items-center gap-2 border-b border-slate-200 bg-white/95 px-5 pb-3 pt-4 backdrop-blur sm:-mx-6 sm:px-6"
    >
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
          <div className="thin-scroll absolute left-0 top-full z-30 mt-1.5 max-h-[60vh] w-72 overflow-y-auto rounded-2xl border border-slate-200 bg-white p-2 shadow-xl">
            <WikiTocList
              headings={headings}
              onNavigate={() => {
                if (tocRef.current) tocRef.current.open = false
              }}
            />
          </div>
        </details>
      )}

      <BarButton
        icon={Download}
        label="다운로드"
        iconOnly
        onClick={handleDownload}
        disabled={downloading}
        className="ml-auto"
      />

      {onOpenChat && (
        <BarButton
          icon={Sparkles}
          label="AI 문서 편집"
          onClick={onOpenChat}
          className={chatButtonClassName}
        />
      )}
    </div>
  )
}

// iconOnly면 글자를 안 그린다 — 다운로드처럼 제목 옆 큰 버튼보다 가벼운 무게가 맞는
// 보조 기능에 쓴다. 글자가 안 보여도 label은 aria-label·title로 남아 스크린리더와
// 마우스 오버 둘 다에서 무슨 버튼인지 알 수 있다.
function BarButton({ icon: Icon, label, iconOnly = false, onClick, disabled = false, className = '' }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      title={label}
      className={`focus-ring inline-flex items-center gap-1.5 rounded-xl border border-slate-200 text-xs font-semibold text-slate-600 transition-colors hover:bg-slate-50 hover:text-primary-600 disabled:pointer-events-none disabled:opacity-50 ${iconOnly ? 'p-2' : 'px-3 py-1.5'} ${className}`}
    >
      <Icon className="size-4 text-slate-400" />
      {!iconOnly && label}
    </button>
  )
}
