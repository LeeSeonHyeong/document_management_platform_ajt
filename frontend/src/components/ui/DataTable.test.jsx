import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import DataTable from './DataTable'

// 열 너비 고정(table-fixed)은 상단 카드 필터로 행이 바뀌어도 열 위치가 그대로여야 한다는
// 요구에서 나왔다 — 행 내용이 달라도 같은 colgroup 이 나오는지가 핵심이다.
const COLUMNS = [
  { key: 'name', header: '이름', width: '30%' },
  { key: 'email', header: '이메일', width: '70%' },
]

describe('DataTable 열 너비', () => {
  it('width 를 준 열이 있으면 table-fixed 와 colgroup 으로 그린다', () => {
    const html = renderToStaticMarkup(
      <DataTable columns={COLUMNS} rows={[{ name: '홍길동', email: 'a@ajt.com' }]} rowKey="email" />,
    )
    expect(html).toContain('table-fixed')
    expect(html).toContain('<colgroup>')
    expect(html).toContain('width:30%')
    expect(html).toContain('width:70%')
  })

  it('행 내용이 달라도 열 너비는 같다', () => {
    const render = (rows) =>
      renderToStaticMarkup(<DataTable columns={COLUMNS} rows={rows} rowKey="email" />)
    const short = render([{ name: '김', email: 'a@ajt.com' }])
    const long = render([{ name: '김민수', email: 'very.long.address@ajt.co.kr' }])
    const colgroupOf = (html) => html.slice(html.indexOf('<colgroup>'), html.indexOf('</colgroup>'))
    expect(colgroupOf(short)).toBe(colgroupOf(long))
  })

  it('문자열 값은 줄바꿈 대신 한 줄로 줄이고 전체 값을 title 로 남긴다', () => {
    const longName = '강인사'.padEnd(40, '○')
    const html = renderToStaticMarkup(
      <DataTable columns={COLUMNS} rows={[{ name: longName, email: 'a@ajt.com' }]} rowKey="email" />,
    )
    expect(html).toContain('truncate')
    expect(html).toContain(`title="${longName}"`)
  })

  it('배지·버튼 같은 노드 값에는 줄임을 걸지 않는다', () => {
    const html = renderToStaticMarkup(
      <DataTable
        columns={[{ key: 'status', header: '상태', width: '100%', render: () => <span>활성</span> }]}
        rows={[{ status: 'active' }]}
        rowKey="status"
      />,
    )
    expect(html).not.toContain('truncate')
    expect(html).not.toContain('title=')
  })

  it('width 가 없으면 기존처럼 내용에 맞춰 그린다', () => {
    const html = renderToStaticMarkup(
      <DataTable columns={[{ key: 'name', header: '이름' }]} rows={[]} rowKey="name" />,
    )
    expect(html).not.toContain('table-fixed')
    expect(html).not.toContain('<colgroup>')
  })
})
