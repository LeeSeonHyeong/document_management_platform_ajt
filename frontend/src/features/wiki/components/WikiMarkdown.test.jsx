// 본문 내부 링크(`pages/{...}.md`)의 마지막 세그먼트는 에이전트 발급 pageKey 또는
// 구형 wikiId 다. S15P11B106-280 이후 pageKey 가 기본이 되면서 wikiId 로만 대조하던
// 화면이 모든 내부 링크를 취소선 처리했다 — 그 회귀를 고정한다. (S15P11B106-300)
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

vi.mock('./MermaidBlock', () => ({ default: () => null }))

import WikiMarkdown from './WikiMarkdown'

function render(markdown, wikiIdByPageKey) {
  return renderToStaticMarkup(
    <MemoryRouter>
      <WikiMarkdown markdown={markdown} wikiIdByPageKey={wikiIdByPageKey} />
    </MemoryRouter>,
  )
}

describe('WikiMarkdown 내부 링크', () => {
  it('pageKey 링크를 해당 wikiId 상세 주소로 되돌린다', () => {
    const html = render(
      '[휴가 규정](pages/a1b2c3d4e5f6.md)',
      new Map([['a1b2c3d4e5f6', '5']]),
    )

    expect(html).toContain('href="/wiki/5"')
  })

  it('구형 wikiId 링크도 계속 동작한다', () => {
    const html = render('[휴가 규정](pages/5.md)', new Map([['5', '5']]))

    expect(html).toContain('href="/wiki/5"')
  })

  it('매핑에 없는 내부 링크는 클릭 불가 안내로 남는다', () => {
    const html = render('[없는 문서](pages/ghost.md)', new Map())

    expect(html).not.toContain('href="/wiki/')
    expect(html).toContain('line-through')
  })
})
