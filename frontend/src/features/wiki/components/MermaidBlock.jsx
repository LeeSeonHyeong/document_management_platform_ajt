import { useEffect, useRef, useState } from 'react'

// mermaid 는 번들이 커서 mermaid 블록이 있는 문서에서만 지연 로딩한다.
// 모듈 스코프에 한 번만 로드·초기화하고 이후엔 캐시된 Promise 를 재사용한다.
let mermaidPromise = null
function loadMermaid() {
  if (!mermaidPromise) {
    mermaidPromise = import('mermaid').then((module) => {
      const mermaid = module.default
      mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'default' })
      return mermaid
    })
  }
  return mermaidPromise
}

let renderSeq = 0

// ```mermaid 코드블록을 SVG 다이어그램으로 렌더한다.
// 실패(문법 오류 등) 시 원본 코드와 안내로 폴백해 화면이 깨지지 않게 한다.
export default function MermaidBlock({ code }) {
  const [svg, setSvg] = useState('')
  const [failed, setFailed] = useState(false)
  // mermaid.render 는 고유 id 가 필요하다. 콜론 없는 안전한 id 를 인스턴스당 1개 만든다.
  const idRef = useRef(`mermaid-${(renderSeq += 1)}`)

  useEffect(() => {
    let cancelled = false
    setFailed(false)
    setSvg('')
    loadMermaid()
      .then((mermaid) => mermaid.render(idRef.current, code))
      .then((result) => {
        if (!cancelled) setSvg(result.svg)
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [code])

  if (failed) {
    return (
      <div className="my-3">
        <p className="mb-1 text-xs font-medium text-rose-500">다이어그램을 표시할 수 없습니다(mermaid 문법 확인).</p>
        <pre className="overflow-x-auto rounded-lg bg-slate-900 p-4 text-sm text-slate-100">{code}</pre>
      </div>
    )
  }

  if (!svg) {
    return <div className="my-3 text-xs text-slate-400">다이어그램을 그리는 중…</div>
  }

  // mermaid 는 securityLevel 'strict' 에서 출력 SVG 를 정화(sanitize)한다.
  return (
    <div
      // 다이어그램을 카드로 감싼다. 배경·여백이 없으면 본문 텍스트와 경계가 흐려
      // "그림"으로 읽히지 않는다. 표와 같은 이유로 본문 폭을 넘어도 된다.
      className="my-4 flex justify-center overflow-x-auto rounded-xl border border-slate-200 bg-slate-50/50 p-4"
      // eslint-disable-next-line react/no-danger
      dangerouslySetInnerHTML={{ __html: svg }}
    />
  )
}
