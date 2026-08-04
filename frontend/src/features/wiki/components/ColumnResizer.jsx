import { useCallback, useRef } from 'react'

// 열 사이 여백을 잡아 끌어 폭을 조절하는 손잡이.
//
// 여백 자체가 손잡이다 — 열 사이에 따로 굵은 띠를 넣으면 화면이 시끄러워진다. 평소에는
// 아무것도 안 보이고, 마우스를 올리면 가운데에 얇은 알약이 뜬다.
//
// **손가락은 눈보다 굵다.** 보이는 알약은 3px 이지만 잡히는 영역은 여백 전체(12px)다.
//
// 마우스만 되면 안 되므로 `role="separator"` + 좌우 방향키로도 조절되고(1회 16px, Shift 시
// 64px), Home 으로 기본값으로 돌아온다. 더블클릭도 기본값이다.
const STEP = 16
const BIG_STEP = 64

export default function ColumnResizer({
  label,
  value,
  min,
  max,
  onChange,
  onCommit,
  invert = false,
  onReset,
  className = '',
}) {
  const drag = useRef(null)

  const handlePointerDown = useCallback(
    (event) => {
      // 왼쪽 버튼(또는 터치)만. 가운데·오른쪽 버튼으로 끌리면 놀란다.
      if (event.button !== 0) return
      event.preventDefault()
      event.currentTarget.setPointerCapture(event.pointerId)
      drag.current = { startX: event.clientX, startValue: value }
      // 끌기 중 본문 글자가 선택돼 파랗게 물드는 것을 막는다.
      document.body.style.userSelect = 'none'
      document.body.style.cursor = 'col-resize'
    },
    [value],
  )

  const handlePointerMove = useCallback(
    (event) => {
      if (!drag.current) return
      const delta = event.clientX - drag.current.startX
      // 오른쪽 열은 손잡이를 **왼쪽으로** 끌 때 넓어진다.
      onChange(drag.current.startValue + (invert ? -delta : delta))
    },
    [invert, onChange],
  )

  const endDrag = useCallback((event) => {
    if (!drag.current) return
    drag.current = null
    document.body.style.userSelect = ''
    document.body.style.cursor = ''
    event.currentTarget.releasePointerCapture?.(event.pointerId)
    // 저장은 끌기가 끝날 때 한 번만 한다 — 끌는 중에는 1초에 수십 번 불린다.
    onCommit?.()
  }, [onCommit])

  const handleKeyDown = useCallback(
    (event) => {
      const step = event.shiftKey ? BIG_STEP : STEP
      const grow = invert ? -step : step
      if (event.key === 'ArrowLeft') onChange(value - grow)
      else if (event.key === 'ArrowRight') onChange(value + grow)
      else if (event.key === 'Home') onReset()
      else return
      event.preventDefault()
      onCommit?.()
    },
    [invert, onChange, onCommit, onReset, value],
  )

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      aria-valuenow={Math.round(value)}
      aria-valuemin={min}
      aria-valuemax={max}
      tabIndex={0}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onDoubleClick={onReset}
      onKeyDown={handleKeyDown}
      title={`${label} — 끌어서 조절, 더블클릭으로 기본값`}
      className={`focus-ring group flex w-3 shrink-0 cursor-col-resize touch-none items-center justify-center rounded-full ${className}`}
    >
      <span className="h-10 w-[3px] rounded-full bg-transparent transition-colors group-hover:bg-slate-300 group-focus-visible:bg-primary-400 group-active:bg-primary-500" />
    </div>
  )
}
