import { useEffect, useState } from 'react'
import { Info } from 'lucide-react'
import { Modal, Button, Input, Select, Textarea, useToast } from '@/components/ui'
import { useCreateDocumentCategory, useUpdateDocumentCategory } from '../queries'

// Figma 4-8-1R — 원본문서 카테고리 추가/수정.
export default function DocumentCategoryFormModal({
  open,
  mode,
  scopeKey,
  category,
  departments = [],
  defaultDepartment = '',
  onDefaultDepartmentChange,
  onClose,
  onSaved,
}) {
  const toast = useToast()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [departmentValue, setDepartmentValue] = useState('')

  const createMutation = useCreateDocumentCategory()
  const updateMutation = useUpdateDocumentCategory(scopeKey)
  const saving = createMutation.isPending || updateMutation.isPending

  useEffect(() => {
    if (!open) return
    setName(mode === 'edit' ? (category?.name ?? '') : '')
    setDescription(mode === 'edit' ? (category?.description ?? '') : '')
    setDepartmentValue(defaultDepartment)
  }, [open, mode, category, defaultDepartment])

  function handleSave() {
    const trimmed = name.trim()
    if (!trimmed || saving) return

    const onSuccess = () => {
      onDefaultDepartmentChange?.(departmentValue)
      onSaved?.()
      onClose?.()
    }
    const onError = (error) => {
      if (error?.response?.status === 409) toast.error('같은 이름의 카테고리가 있습니다.')
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
          이름과 기본 공개 부서를 변경할 수 있습니다.
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
        <Select
          label="기본 공개 부서"
          value={departmentValue}
          onChange={(event) => setDepartmentValue(event.target.value)}
          options={[
            { value: '', label: '부서별 지정' },
            { value: 'ALL', label: '전체 공개' },
            ...departments.map((department) => ({
              value: String(department.departmentId),
              label: department.name,
            })),
          ]}
          className="text-left"
        />
        <Textarea
          label="설명"
          hint="선택"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          placeholder="이 카테고리에 대한 간단한 설명"
          rows={3}
          className="h-24 resize-none overflow-y-auto"
        />
        <div className="flex items-center gap-2 rounded-xl bg-primary-50 px-3 py-2.5 text-xs text-slate-500">
          <Info className="size-4 shrink-0 text-primary-500" />
          이름을 바꿔도 기존 문서의 분류는 그대로 유지됩니다.
        </div>
      </div>
    </Modal>
  )
}
