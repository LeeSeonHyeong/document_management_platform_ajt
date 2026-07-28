import * as React from 'react'

import { GraphViewer } from './GraphViewer'
import type { GraphData } from './types'

export function App() {
  const [data, setData] = React.useState<GraphData | null>(null)
  const [error, setError] = React.useState<string | null>(null)
  const [loading, setLoading] = React.useState(true)
  const [showSources, setShowSources] = React.useState(false)
  const [showIndex, setShowIndex] = React.useState(true)
  // Monotonic: a slow response must never overwrite fresher data (from upstream).
  const seqRef = React.useRef(0)

  const load = React.useCallback(() => {
    const seq = ++seqRef.current
    setLoading(true)
    setError(null)
    fetch('/graph')
      .then((res) => (res.ok ? res.json() : res.json().then((b) => Promise.reject(b.error))))
      .then((body: GraphData) => {
        if (seq === seqRef.current) setData(body)
      })
      .catch((err) => {
        if (seq === seqRef.current) setError(String(err?.message ?? err))
      })
      .finally(() => {
        if (seq === seqRef.current) setLoading(false)
      })
  }, [])

  React.useEffect(load, [load])

  const stats = React.useMemo(() => {
    if (!data) return null
    const pages = data.nodes.filter((n) => n.kind === 'page')
    const categories = new Set(pages.map((n) => n.category).filter(Boolean))
    return {
      pages: pages.length,
      sources: data.nodes.filter((n) => n.kind === 'source').length,
      categories: categories.size,
      links: data.edges.filter((e) => e.type === 'links_to').length,
      citations: data.edges.filter((e) => e.type === 'cites').length,
      quoted: data.edges.filter((e) => e.type === 'cites' && e.quote).length,
      orphans: pages.filter((n) => n.orphan).length,
    }
  }, [data])

  return (
    <main className="app">
      <header className="bar">
        <div className="bar-id">
          <h1>위키 그래프</h1>
          {data && <span className="scope">{data.scope}</span>}
        </div>

        {stats && (
          <dl className="stats">
            <Stat label="페이지" value={stats.pages} />
            <Stat label="카테고리" value={stats.categories} />
            <Stat label="페이지 링크" value={stats.links} />
            <Stat label="각주" value={stats.citations} />
            <Stat label="원문 인용" value={stats.quoted} />
            <Stat label="원본문서" value={stats.sources} />
            <Stat label="고아" value={stats.orphans} tone={stats.orphans > 0 ? 'warn' : undefined} />
          </dl>
        )}

        <div className="controls">
          <Toggle on={showIndex} onClick={() => setShowIndex((v) => !v)} label="목차" />
          <Toggle on={showSources} onClick={() => setShowSources((v) => !v)} label="원본문서" />
          <button className="btn" onClick={load} disabled={loading}>
            {loading ? '읽는 중' : '다시 읽기'}
          </button>
        </div>
      </header>

      <section className="stage">
        {error ? (
          <div className="empty">
            <p>그래프를 읽지 못했다: {error}</p>
            <p className="hint">
              <code>uv run python -m wiki_mcp.graph_api --root ./data --scope ALL</code> 를 먼저 띄운다.
            </p>
          </div>
        ) : data ? (
          <GraphViewer data={data} showSources={showSources} showIndex={showIndex} />
        ) : (
          <p className="empty">읽는 중…</p>
        )}
      </section>

      <footer className="legend">
        <span className="key"><i className="dot page" />위키 페이지</span>
        <span className="key"><i className="dot index" />목차</span>
        <span className="key"><i className="dot source" />원본문서</span>
        <span className="key"><i className="ring" />고아 페이지</span>
        <span className="key-note">
          색은 종류. 카테고리는 위치로 묶인다 — 클러스터 이름이 바깥에 붙는다.
          점 크기는 각주 수. 각주 선에 올리면 원문 인용문이 나온다.
        </span>
      </footer>
    </main>
  )
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: 'warn' }) {
  return (
    <div className={tone ? `stat ${tone}` : 'stat'}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

function Toggle({ on, onClick, label }: { on: boolean; onClick: () => void; label: string }) {
  return (
    <button className="btn" onClick={onClick} aria-pressed={on} data-on={on}>
      {label}
    </button>
  )
}
