import { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { cn } from '@/shared/lib/cn'

// 제어형 탭. items=[{ value, label, icon }], value/onChange로 제어한다.
// 탭이 컨테이너 폭을 넘으면 가로로 스크롤한다. 각 탭은 shrink-0·whitespace-nowrap 이라
// 폭이 눌리지 않는다 — 한글 라벨이 글자 단위로 세로로 쪼개지는 깨짐을 막는다.
//
// **넘칠 때의 모양.** 예전에는 굵은 가로 스크롤바가 탭 아래에 늘 깔려 있었다(부서가 늘거나
// 부서명이 길면 항상). 스크롤바는 숨기고, 넘칠 때만 줄 **바깥 좌우**에 화살표 버튼을 세운다.
// 탭 위에 무엇도 겹치지 않고(그라데이션으로 덮으면 글자가 반쯤 지워져 보인다), 버튼 자리가
// 고정이라 스크롤하는 동안 줄 높이나 탭 위치가 흔들리지 않는다.
// 세로 스크롤은 아예 막는다(가로로만 움직이는 줄이라 세로 막대가 뜨면 사고다).
const LABEL_MAX = 'max-w-[12rem]'

export default function Tabs({ items, value, onChange, className }) {
  const scrollerRef = useRef(null)
  // 어느 쪽에 가려진 탭이 남아 있는지. 그 쪽에만 그라데이션·화살표를 띄운다.
  const [more, setMore] = useState({ left: false, right: false })

  const syncMore = useCallback(() => {
    const scroller = scrollerRef.current
    if (!scroller) return
    const max = scroller.scrollWidth - scroller.clientWidth
    // 1px 여유: 소수점 폭에서 끝까지 밀어도 max 에 정확히 닿지 않는 브라우저가 있다.
    setMore({ left: scroller.scrollLeft > 1, right: scroller.scrollLeft < max - 1 })
  }, [])

  // 탭 개수·창 폭이 바뀌면 넘침 여부도 달라진다.
  useEffect(() => {
    const scroller = scrollerRef.current
    if (!scroller) return
    syncMore()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(syncMore)
    observer?.observe(scroller)
    return () => observer?.disconnect()
  }, [syncMore, items])

  // 선택된 탭이 가려진 채로 시작할 수 있다(저장된 필터로 들어오는 경우). 보이는 자리로 당겨온다.
  useEffect(() => {
    const active = scrollerRef.current?.querySelector('[aria-selected="true"]')
    active?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  }, [value])

  const scrollStep = (direction) => {
    const scroller = scrollerRef.current
    if (!scroller) return
    scroller.scrollBy({
      left: direction * Math.max(160, scroller.clientWidth * 0.7),
      behavior: 'smooth',
    })
  }

  // 넘칠 때만 화살표 자리를 만든다. 한 번 생기면 한쪽 끝에 닿아도 자리는 유지하고 흐리게만
  // 바꾼다 — 스크롤 도중 버튼이 사라졌다 나타나면 탭이 좌우로 튄다.
  const overflowing = more.left || more.right

  return (
    // 밑줄은 화살표 자리까지 이어져야 한 줄로 보인다 — 스크롤 영역과 버튼이 각자 긋는다.
    <div className={cn('flex items-stretch', className)}>
      {overflowing && (
        <StepButton side="left" disabled={!more.left} onClick={() => scrollStep(-1)} />
      )}
      <div
        ref={scrollerRef}
        onScroll={syncMore}
        role="tablist"
        className="flex min-w-0 flex-1 gap-1 overflow-x-auto overflow-y-hidden border-b border-slate-200 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
      >
        {items.map((item) => {
          const active = item.value === value
          return (
            <button
              key={item.value}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => onChange(item.value)}
              className={cn(
                'focus-ring -mb-px flex shrink-0 items-center gap-1.5 whitespace-nowrap border-b-2 px-4 py-2.5 text-sm font-medium transition-colors',
                active
                  ? 'border-primary-600 text-primary-700'
                  : 'border-transparent text-slate-500 hover:text-slate-700',
              )}
            >
              {item.icon}
              <span
                className={cn('truncate', LABEL_MAX)}
                title={typeof item.label === 'string' ? item.label : undefined}
              >
                {item.label}
              </span>
            </button>
          )
        })}
      </div>

      {overflowing && (
        <StepButton side="right" disabled={!more.right} onClick={() => scrollStep(1)} />
      )}
    </div>
  )
}

// 탭 줄 바깥에 서는 이동 버튼. 탭 위에 겹치지 않으므로 글자가 가려지지 않는다.
// 더 갈 곳이 없으면 없애지 않고 흐리게 둔다(자리가 사라지면 탭이 좌우로 튄다).
function StepButton({ side, disabled, onClick }) {
  const isLeft = side === 'left'
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={isLeft ? '이전 탭 보기' : '다음 탭 보기'}
      className={cn(
        'focus-ring flex w-8 shrink-0 items-center justify-center border-b border-slate-200 text-slate-400 transition-colors',
        disabled ? 'cursor-default opacity-30' : 'hover:text-primary-600',
      )}
    >
      {isLeft ? <ChevronLeft className="size-4" /> : <ChevronRight className="size-4" />}
    </button>
  )
}
