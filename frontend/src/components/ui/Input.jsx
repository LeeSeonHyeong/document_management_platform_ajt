import { forwardRef, useId } from 'react'
import { cn } from '@/shared/lib/cn'
import Field from './Field'
import { controlClass } from './fieldStyles'

// 텍스트 입력. react-hook-form register와 함께 쓰도록 forwardRef.
// leftIcon으로 검색/이메일 등 아이콘을 앞에 붙일 수 있다.
const Input = forwardRef(function Input(
  { label, required, hint, error, leftIcon, className, id, ...props },
  ref,
) {
  const autoId = useId()
  const inputId = id ?? autoId
  return (
    <Field id={inputId} label={label} required={required} hint={hint} error={error}>
      <div className="relative">
        {leftIcon && (
          <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-slate-400">
            {leftIcon}
          </span>
        )}
        <input
          ref={ref}
          id={inputId}
          aria-invalid={Boolean(error)}
          className={cn(controlClass(Boolean(error)), leftIcon && 'pl-9', className)}
          {...props}
        />
      </div>
    </Field>
  )
})

export default Input
