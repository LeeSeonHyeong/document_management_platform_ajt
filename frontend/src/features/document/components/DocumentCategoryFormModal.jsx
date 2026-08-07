import { useEffect, useState } from 'react'
import { Info } from 'lucide-react'
import { Modal, Button, Field, Input, Textarea, useToast } from '@/components/ui'
import { useCreateDocumentCategory, useUpdateDocumentCategory } from '../queries'
import DepartmentMultiSelect from './DepartmentMultiSelect'

// 추가 화면과 같은 이름 제한(20자)에 맞춘다. 설명은 DB(VARCHAR 1000) 안에서 넉넉히 200자로 막는다.
// (S15P11B106-322: 수정 모달에는 입력 제한이 없어 추가 화면과 규칙이 어긋났다.)
const CATEGORY_NAME_MAX_LENGTH = 20
const CATEGORY_DESCRIPTION_MAX_LENGTH = 200

// Figma 4-8-1R — 원본문서 카테고리 추가/수정.
export default function DocumentCategoryFormModal({
  open,
  mode,
  scopeKey,
  category,
  departments = [],
  // 부서관리자 제한(S15P11B106-292). 담당 부서를 빼거나 전사 범위로 바꿀 수 없다.
  requiredDepartmentId = null,
  allowAllScope = true,
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
          maxLength={CATEGORY_NAME_MAX_LENGTH}
          onChange={(event) => setName(event.target.value)}
          placeholder="카테고리 이름"
          error={
            name.length >= CATEGORY_NAME_MAX_LENGTH
              ? `* 카테고리명은 최대 ${CATEGORY_NAME_MAX_LENGTH}자까지 입력할 수 있습니다.`
              : undefined
          }
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
                requiredDepartmentId={requiredDepartmentId}
                allowAllScope={allowAllScope}
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
          maxLength={CATEGORY_DESCRIPTION_MAX_LENGTH}
          onChange={(event) => setDescription(event.target.value)}
          placeholder="이 카테고리에 대한 간단한 설명 (선택)"
          rows={2}
          className="h-16 resize-none overflow-y-auto"
          error={
            description.length >= CATEGORY_DESCRIPTION_MAX_LENGTH
              ? `* 설명은 최대 ${CATEGORY_DESCRIPTION_MAX_LENGTH}자까지 입력할 수 있습니다.`
              : undefined
          }
        />
        <div className="flex items-center gap-2 rounded-xl bg-primary-50 px-3 py-2.5 text-xs text-slate-500">
          <Info className="size-4 shrink-0 text-primary-500" />
          이름을 바꿔도 기존 문서 {documentCount}건의 분류는 그대로 유지됩니다.
        </div>
      </div>
    </Modal>
  )
}
