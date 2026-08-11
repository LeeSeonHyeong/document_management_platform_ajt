import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AffectedWikiContent } from './AffectedWikiDisplay'

function render(wikis, expanded = false) {
  return renderToStaticMarkup(
    <MemoryRouter>
      <AffectedWikiContent wikis={wikis} expanded={expanded} onToggle={() => {}} />
    </MemoryRouter>,
  )
}

describe('작업 결과의 영향 Wiki 표시', () => {
  const wikis = [
    { wikiId: '21', title: '폐지된 규정', deleted: true },
    { wikiId: '22', title: '살아 있는 규정', deleted: false },
    { wikiId: '23', title: '보안 안내', deleted: false },
  ]

  it('첫 Wiki가 삭제됐으면 링크 없이 삭제됨을 보이고 나머지 건수를 열 수 있다', () => {
    const html = render(wikis)

    expect(html).toContain('폐지된 규정')
    expect(html).toContain('삭제됨')
    expect(html).toContain('외 2건')
    expect(html).not.toContain('href="/wiki/21"')
  })

  it('펼친 목록에서 삭제 Wiki는 링크하지 않고 살아 있는 Wiki만 상세로 연결한다', () => {
    const html = render(wikis, true)

    expect(html).not.toContain('href="/wiki/21"')
    expect(html).toContain('href="/wiki/22"')
    expect(html).toContain('href="/wiki/23"')
  })

  it('영향 Wiki가 없으면 열 가운데에 빈값을 표시한다', () => {
    expect(render([])).toContain('class="flex justify-center text-slate-400">-</span>')
  })
})
