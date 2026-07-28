import { forwardRef, useId } from 'react'
import { ChevronDown } from 'lucide-react'
import { cn } from '@/shared/lib/cn'
import Field from './Field'
import { controlClass } from './fieldStyles'

// 네이티브 select 기반. options=[{ value, label }] 또는 children 직접 전달.
const Select = forwardRef(function Select(
  { label, required, hint, error, options, placeholder, className, id, children, ...props },
  ref,
) {
  const autoId = useId()
  const inputId = id ?? autoId
  return (
    <Field id={inputId} label={label} required={required} hint={hint} error={error}>
      <div className="relative">
        <select
          ref={ref}
          id={inputId}
          aria-invalid={Boolean(error)}
          className={cn(controlClass(Boolean(error)), 'appearance-none pr-9', className)}
          {...props}
        >
          {placeholder && (
            <option value="" disabled>
              {placeholder}
            </option>
          )}
          {options
            ? options.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))
            : children}
        </select>
        <ChevronDown className="pointer-events-none absolute inset-y-0 right-3 my-auto size-4 text-slate-400" />
      </div>
    </Field>
  )
})

export default Select
