import { describe, it, expect, beforeEach } from 'vitest'
import { ARTICLE_MIN, CHAT, TREE, clampWidth, loadWidth, saveWidth } from './panelWidths'

describe('clampWidth', () => {
  it('범위 안의 값은 그대로 둔다', () => {
    expect(clampWidth(TREE, 300)).toBe(300)
  })

  it('최소·최대를 넘으면 자른다', () => {
    expect(clampWidth(TREE, 10)).toBe(TREE.min)
    expect(clampWidth(TREE, 9999)).toBe(TREE.max)
  })

  it('숫자가 아니면 기본값이다', () => {
    expect(clampWidth(TREE, Number.NaN)).toBe(TREE.default)
    expect(clampWidth(CHAT, undefined)).toBe(CHAT.default)
  })

  it('본문 최소 폭을 침범하지 않는 선까지만 넓어진다', () => {
    // 나눠 쓸 폭 700 → 열은 최대 700-420 = 280 까지만.
    expect(clampWidth(TREE, 400, 700)).toBe(700 - ARTICLE_MIN)
  })

  it('본문 최소 폭조차 안 나오는 좁은 화면에서는 열의 최소 폭을 준다', () => {
    // 이 폭에서는 CSS 가 열을 숨기므로(서랍으로 간다) 계산이 음수로 새면 안 된다는 것만 본다.
    expect(clampWidth(CHAT, 400, 300)).toBe(CHAT.min)
  })

  it('소수점을 남기지 않는다 — 끌기 중 폭이 0.5px 씩 흔들리면 글자가 떨린다', () => {
    expect(clampWidth(TREE, 240.6)).toBe(241)
  })
})

// 이 프로젝트의 테스트는 node 환경에서 돌아 `localStorage` 가 없다. 실제 브라우저 API 를
// 흉내내는 대역을 끼워 저장·복원 동작만 본다(예외 경로는 아래 별도 테스트에서 본다).
function stubStorage() {
  const map = new Map()
  globalThis.localStorage = {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => map.set(key, String(value)),
    removeItem: (key) => map.delete(key),
    clear: () => map.clear(),
  }
}

describe('loadWidth · saveWidth', () => {
  beforeEach(stubStorage)

  it('저장한 폭을 다시 읽는다', () => {
    saveWidth(TREE, 312)
    expect(loadWidth(TREE)).toBe(312)
  })

  it('저장된 값이 없으면 기본값이다', () => {
    expect(loadWidth(CHAT)).toBe(CHAT.default)
  })

  it('저장된 값이 범위를 벗어나 있으면 잘라서 읽는다', () => {
    // 예전 버전이 남긴 값이나 손으로 고친 값이 들어와도 화면이 깨지지 않아야 한다.
    globalThis.localStorage.setItem('ajt.wiki.panelWidth.tree', '99999')
    expect(loadWidth(TREE)).toBe(TREE.max)
  })

  it('숫자가 아닌 값이 저장돼 있으면 기본값이다', () => {
    globalThis.localStorage.setItem('ajt.wiki.panelWidth.chat', 'wide')
    expect(loadWidth(CHAT)).toBe(CHAT.default)
  })

  it('두 열이 서로 다른 키를 쓴다', () => {
    saveWidth(TREE, 200)
    saveWidth(CHAT, 500)
    expect(loadWidth(TREE)).toBe(200)
    expect(loadWidth(CHAT)).toBe(500)
  })

  it('localStorage 자체가 던져도 화면은 살아 있다', () => {
    // 사파리 프라이빗 모드에서 실제로 던진다. 폭 조절이 안 되는 것과 화면이 죽는 것은 다르다.
    globalThis.localStorage = {
      getItem() {
        throw new Error('SecurityError')
      },
      setItem() {
        throw new Error('SecurityError')
      },
    }
    expect(loadWidth(TREE)).toBe(TREE.default)
    expect(() => saveWidth(TREE, 300)).not.toThrow()
  })

  it('localStorage 가 아예 없어도 기본값으로 돈다', () => {
    delete globalThis.localStorage
    expect(loadWidth(CHAT)).toBe(CHAT.default)
    expect(() => saveWidth(CHAT, 400)).not.toThrow()
  })
})
