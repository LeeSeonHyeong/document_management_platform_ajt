import { forwardRef, useId } from 'react'
import { cn } from '@/shared/lib/cn'
import Field from './Field'
import { controlClass } from './fieldStyles'

const Textarea = forwardRef(function Textarea(
  { label, required, hint, error, rows = 4, className, id, ...props },
  ref,
) {
  const autoId = useId()
  const inputId = id ?? autoId
  return (
    <Field id={inputId} label={label} required={required} hint={hint} error={error}>
      <textarea
        ref={ref}
        id={inputId}
        rows={rows}
        aria-invalid={Boolean(error)}
        className={cn(controlClass(Boolean(error)), 'resize-y', className)}
        {...props}
      />
    </Field>
  )
})

export default Textarea
