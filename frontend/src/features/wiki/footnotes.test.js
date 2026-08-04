import { describe, it, expect } from 'vitest'
import { collectFootnotes, parseFootnoteDefinition, footnoteLabelFromHref } from './footnotes'

// 아래 형태는 실제 생성된 위키에서 관측된 것들이다.

describe('parseFootnoteDefinition', () => {
  it('파일명·위치·인용문을 쪼갠다', () => {
    expect(
      parseFootnoteDefinition('08-compensation.md, Equity — "We offer average equity."'),
    ).toEqual({
      source: '08-compensation.md',
      location: 'Equity',
      quote: 'We offer average equity.',
    })
  })

  it('인용문이 없으면 위치까지만 읽는다', () => {
    expect(parseFootnoteDefinition('인사규정.pdf, 12쪽')).toEqual({
      source: '인사규정.pdf',
      location: '12쪽',
      quote: null,
    })
  })

  it('파일명만 있어도 읽는다', () => {
    expect(parseFootnoteDefinition('02-side-gigs.md')).toEqual({
      source: '02-side-gigs.md',
      location: null,
      quote: null,
    })
  })

  it('굽은 따옴표도 인용문으로 읽는다', () => {
    // AI 가 만든 위키에 곧은/굽은 따옴표가 섞여 있다(AI 서버 쪽에서 겪은 문제).
    expect(parseFootnoteDefinition('a.md, 위치 — “인용문”').quote).toBe('인용문')
  })

  it('인용문 안의 따옴표가 겹쳐도 끝까지 잡는다', () => {
    const parsed = parseFootnoteDefinition('a.md, 위치 — "not after a "probation period" or so"')
    expect(parsed.quote).toBe('not after a "probation period" or so')
  })

  it('빈 값은 null 이다', () => {
    expect(parseFootnoteDefinition('')).toBeNull()
    expect(parseFootnoteDefinition(null)).toBeNull()
  })
})

describe('collectFootnotes', () => {
  it('줄 시작의 정의만 모은다', () => {
    const markdown = [
      '본문에서 각주를 쓴다[^1]. 그리고 목록을 여는 문장[^2]:',
      '',
      '- 항목',
      '',
      '[^1]: a.md, 1절 — "첫 인용문"',
      '[^2]: b.md, 2절 — "둘째 인용문"',
    ].join('\n')

    const notes = collectFootnotes(markdown)
    expect(Object.keys(notes)).toEqual(['1', '2'])
    expect(notes['1'].quote).toBe('첫 인용문')
    expect(notes['2'].source).toBe('b.md')
  })

  it('본문 중간에서 각주 뒤에 콜론이 와도 정의로 오판하지 않는다', () => {
    // `...한다[^7]:` 로 목록을 여는 정상적인 문장 — AI 서버 lint 가 같은 이유로 줄 위치를 본다.
    const notes = collectFootnotes('규칙은 다음과 같다[^7]:\n\n- 항목\n')
    expect(notes).toEqual({})
  })

  it('정의가 없으면 빈 객체다', () => {
    expect(collectFootnotes('각주 없는 본문')).toEqual({})
    expect(collectFootnotes(null)).toEqual({})
  })

  it('여러 번 불러도 같은 결과다', () => {
    // 정규식에 `g` 플래그가 있어 `lastIndex` 가 남으면 두 번째 호출이 비어 버린다.
    const markdown = '본문[^1].\n\n[^1]: a.md, 1절 — "인용문"'
    expect(collectFootnotes(markdown)).toEqual(collectFootnotes(markdown))
  })
})

describe('footnoteLabelFromHref', () => {
  it('각주 참조 href 에서 라벨을 뽑는다', () => {
    expect(footnoteLabelFromHref('#user-content-fn-1')).toBe('1')
    expect(footnoteLabelFromHref('#user-content-fn-12')).toBe('12')
  })

  it('각주가 아니면 null 이다', () => {
    expect(footnoteLabelFromHref('/wiki/10')).toBeNull()
    expect(footnoteLabelFromHref('https://example.com')).toBeNull()
    expect(footnoteLabelFromHref(undefined)).toBeNull()
  })
})
