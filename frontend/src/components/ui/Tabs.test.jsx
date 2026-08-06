import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import Tabs from './Tabs'

// 부서명이 아주 길면 그 탭 하나가 화면을 가로질러 굵은 스크롤바가 늘 떠 있었다.
describe('Tabs 라벨', () => {
  it('긴 라벨은 폭을 제한해 줄이고 전체 값을 title 로 남긴다', () => {
    const longLabel = '인사부'.padEnd(40, '○')
    const html = renderToStaticMarkup(
      <Tabs
        items={[{ value: 'all', label: '전체 일정' }, { value: 'd1', label: longLabel }]}
        value="all"
        onChange={() => {}}
      />,
    )
    expect(html).toContain('max-w-[12rem]')
    expect(html).toContain('truncate')
    expect(html).toContain(`title="${longLabel}"`)
  })

  it('스크롤바는 감추고 가로로만 움직인다', () => {
    const html = renderToStaticMarkup(
      <Tabs items={[{ value: 'all', label: '전체 일정' }]} value="all" onChange={() => {}} />,
    )
    expect(html).toContain('[scrollbar-width:none]')
    expect(html).toContain('overflow-y-hidden')
    // 넘치지 않으면 화살표도 나오지 않는다.
    expect(html).not.toContain('다음 탭 보기')
  })
})
