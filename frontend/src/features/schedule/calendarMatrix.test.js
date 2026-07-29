import { describe, it, expect } from 'vitest'
import { layoutMonth } from './calendarMatrix'

// 기준 달: 2026년 7월. 7/1은 수요일이라 그리드는 6/28(일)부터 시작한다.
// 주 구성: [6/28~7/4], [7/5~11], [7/12~18], [7/19~25], [7/26~8/1] → 5주.
const JULY_2026 = new Date(2026, 6, 15)

function event(id, start, end, visibilityType = 'personal') {
  return { id, title: id, start, end, visibilityType }
}

describe('layoutMonth', () => {
  it('월 그리드는 일요일 시작·7일 주로 구성된다', () => {
    const { weeks, monthStart } = layoutMonth(JULY_2026, [])
    expect(monthStart.getMonth()).toBe(6) // 7월(0-index)
    expect(weeks).toHaveLength(5)
    expect(weeks[0].days).toHaveLength(7)
    expect(weeks[0].days[0].date.getDay()).toBe(0) // 일요일
    // 그리드 첫날은 6/28(전월), 7/1은 첫 주의 col3(수)
    expect(weeks[0].days[0].isCurrentMonth).toBe(false)
    expect(weeks[0].days[3].isCurrentMonth).toBe(true)
    expect(weeks[0].days[3].date.getDate()).toBe(1)
  })

  it('당일 일정은 colSpan 1이고 isStart·isEnd가 모두 참', () => {
    const { weeks } = layoutMonth(JULY_2026, [
      event('A', new Date(2026, 6, 15, 9), new Date(2026, 6, 15, 10)),
    ])
    const segs = weeks.flatMap((w) => w.segments)
    expect(segs).toHaveLength(1)
    expect(segs[0].colSpan).toBe(1)
    expect(segs[0].isStart).toBe(true)
    expect(segs[0].isEnd).toBe(true)
    expect(segs[0].colStart).toBe(3) // 7/15는 수요일
  })

  it('여러 주에 걸친 일정은 주별 세그먼트로 잘리고 경계 플래그가 정확하다', () => {
    // 7/1(수) ~ 7/8(수): 1주차와 2주차에 걸침
    const { weeks } = layoutMonth(JULY_2026, [event('B', new Date(2026, 6, 1), new Date(2026, 6, 8))])
    const w0 = weeks[0].segments
    const w1 = weeks[1].segments
    expect(w0).toHaveLength(1)
    expect(w1).toHaveLength(1)
    // 1주차: 7/1(col3)~7/4(col6), 시작만 참
    expect(w0[0].colStart).toBe(3)
    expect(w0[0].colSpan).toBe(4)
    expect(w0[0].isStart).toBe(true)
    expect(w0[0].isEnd).toBe(false)
    // 2주차: 7/5(col0)~7/8(col3), 종료만 참
    expect(w1[0].colStart).toBe(0)
    expect(w1[0].colSpan).toBe(4)
    expect(w1[0].isStart).toBe(false)
    expect(w1[0].isEnd).toBe(true)
  })

  it('같은 날 겹치는 일정은 서로 다른 레인에 배정된다', () => {
    const { weeks } = layoutMonth(JULY_2026, [
      event('A', new Date(2026, 6, 15, 9), new Date(2026, 6, 15, 10)),
      event('B', new Date(2026, 6, 15, 9), new Date(2026, 6, 15, 10)),
    ])
    const week = weeks[2] // 7/12~18
    expect(week.segments).toHaveLength(2)
    expect(week.segments.map((s) => s.lane).sort()).toEqual([0, 1])
  })

  it('maxLanes를 초과하면 초과분이 overflowByCol로 접힌다', () => {
    const evs = [0, 1, 2, 3].map((i) =>
      event(`E${i}`, new Date(2026, 6, 15, 9), new Date(2026, 6, 15, 10), 'all'),
    )
    const { weeks } = layoutMonth(JULY_2026, evs, { maxLanes: 3 })
    const week = weeks[2] // 7/15 포함 주
    expect(week.segments).toHaveLength(3) // 보이는 건 3개
    expect(week.segments.map((s) => s.lane).sort()).toEqual([0, 1, 2])
    expect(week.overflowByCol[3]).toBe(1) // 7/15 열에 1개 숨김
  })
})
