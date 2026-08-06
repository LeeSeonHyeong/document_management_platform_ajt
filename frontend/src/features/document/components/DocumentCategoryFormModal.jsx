import { useEffect, useState } from 'react'
import { Info } from 'lucide-react'
import { Modal, Button, Field, Input, Textarea, useToast } from '@/components/ui'
import { useCreateDocumentCategory, useUpdateDocumentCategory } from '../queries'
import DepartmentMultiSelect from './DepartmentMultiSelect'

// Figma 4-8-1R — 원본문서 카테고리 추가/수정.
export default function DocumentCategoryFormModal({
  open,
  mode,
  scopeKey,
  category,
  departments = [],
  defaultDepartments = [],
  departmentLabel = '부서별 지정',
  documentCount = 0,
  onDefaultDepartmentsChange,
  onClose,
  onSaved,
}) {
  const toast = useToast()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [departmentValues, setDepartmentValues] = useState([])

  const createMutation = useCreateDocumentCategory()
  const updateMutation = useUpdateDocumentCategory()
  const saving = createMutation.isPending || updateMutation.isPending

  useEffect(() => {
    if (!open) return
    setName(mode === 'edit' ? (category?.name ?? '') : '')
    setDescription(mode === 'edit' ? (category?.description ?? '') : '')
    setDepartmentValues(defaultDepartments)
  }, [open, mode, category, defaultDepartments])

  function handleSave() {
    const trimmed = name.trim()
    if (!trimmed || saving) return

    const onSuccess = () => {
      if (mode === 'edit' && documentCount === 0) {
        onDefaultDepartmentsChange?.(departmentValues)
      }
      onSaved?.()
      onClose?.()
    }
    const onError = (error) => {
      if (error?.status === 409) toast.error('같은 이름의 카테고리가 있습니다.')
      else toast.error('저장에 실패했습니다.')
    }

    if (mode === 'edit') {
      updateMutation.mutate(
        { categoryId: category.documentCategoryId, name: trimmed, description },
        { onSuccess, onError },
      )
    } else {
      createMutation.mutate({ scopeKey, name: trimmed, description }, { onSuccess, onError })
    }
  }

  return (
    <Modal
      open={open}
      onClose={saving ? undefined : onClose}
      closeOnOverlay={!saving}
      showClose={false}
      size="lg"
      footerClassName="bg-slate-50 px-6 py-4"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={saving}>
            취소
          </Button>
          <Button onClick={handleSave} loading={saving} disabled={!name.trim()}>
            저장
          </Button>
        </>
      }
    >
      <div>
        <h2 className="text-xl font-bold text-slate-900">
          {mode === 'edit' ? '카테고리 수정' : '카테고리 추가'}
        </h2>
        <p className="mt-1 text-sm text-slate-500">
          {mode === 'edit' ? '이름과 설명을 변경할 수 있습니다.' : '새 카테고리를 추가합니다.'}
        </p>
      </div>

      <div className="mt-5 space-y-4">
        <Input
          label="카테고리명"
          required
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="카테고리 이름"
        />
        {mode === 'edit' && (
          <Field label="공개 부서">
            {documentCount === 0 ? (
              <DepartmentMultiSelect
                value={departmentValues}
                departments={departments}
                onChange={setDepartmentValues}
                placeholder="공개 부서 (선택)"
                allowWrap
              />
            ) : (
              <div className="flex min-h-10 items-center rounded-lg border border-slate-200 bg-slate-100 px-3 py-2">
                <span className="whitespace-normal break-keep text-sm text-slate-500">
                  {departmentLabel}
                </span>
              </div>
            )}
            {documentCount > 0 && (
              <p className="text-left text-xs text-rose-500">
                * 해당 카테고리에 문서가 존재합니다.
              </p>
            )}
          </Field>
        )}
        <Textarea
          label="설명"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          placeholder="이 카테고리에 대한 간단한 설명 (선택)"
          rows={2}
          className="h-16 resize-none overflow-y-auto"
        />
        <div className="flex items-center gap-2 rounded-xl bg-primary-50 px-3 py-2.5 text-xs text-slate-500">
          <Info className="size-4 shrink-0 text-primary-500" />
          이름을 바꿔도 기존 문서 {documentCount}건의 분류는 그대로 유지됩니다.
        </div>
      </div>
    </Modal>
  )
}
