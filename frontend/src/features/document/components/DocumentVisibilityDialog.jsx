import { useEffect, useState } from 'react'
import { Modal, Button, Chip } from '@/components/ui'
import { useDepartments } from '@/features/department/queries'
import { useUpdateDocument } from '../queries'
import { buildScopeKey } from '../scope'

// Figma 4-2-1R — AI 작업 대기 목록(4-2R)의 각 행에서 문서 1건의 공개 범위를 다시 지정한다.
// PATCH /documents/:id 의 departmentIds 는 "배열"이다(업로드 시의 콤마 문자열과 다름).
export default function DocumentVisibilityDialog({ open, onClose, document, onSaved }) {
  const { data: departments = [] } = useDepartments()
  const updateMutation = useUpdateDocument(document?.documentId)

  const [visibilityType, setVisibilityType] = useState('all')
  const [departmentIds, setDepartmentIds] = useState([])

  // 다이얼로그가 열릴 때 대상 문서의 현재 값으로 초기화한다.
  useEffect(() => {
    if (!open || !document) return
    setVisibilityType(document.visibilityType ?? 'all')
    setDepartmentIds((document.departments ?? []).map((d) => d.departmentId))
  }, [open, document])

  const saving = updateMutation.isPending
  const invalid = visibilityType === 'department' && departmentIds.length === 0

  // 현재 선택으로 만들어질 scopeKey. 기존과 다르면 작업 묶음이 나뉠 수 있음을 안내한다.
  const nextScopeKey = buildScopeKey(visibilityType, departmentIds)
  const scopeChanged = Boolean(document) && nextScopeKey !== document.scopeKey

  function toggleDepartment(id) {
    setDepartmentIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
  }

  function handleSave() {
    if (invalid || saving) return
    updateMutation.mutate(
      {
        documentCategoryId: document.documentCategoryId,
        visibilityType,
        departmentIds,
      },
      {
        onSuccess: () => {
          onSaved?.()
          onClose?.()
        },
      },
    )
  }

  return (
    <Modal
      open={open}
      onClose={saving ? undefined : onClose}
      closeOnOverlay={!saving}
      title="공개 범위 지정"
      description="이 문서를 열람할 수 있는 범위를 지정합니다."
      size="md"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={saving}>
            취소
          </Button>
          <Button variant="primary" onClick={handleSave} loading={saving} disabled={invalid}>
            저장
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="flex gap-2">
          <Button
            size="sm"
            variant={visibilityType === 'all' ? 'primary' : 'outline'}
            onClick={() => setVisibilityType('all')}
            disabled={saving}
          >
            전체
          </Button>
          <Button
            size="sm"
            variant={visibilityType === 'department' ? 'primary' : 'outline'}
            onClick={() => setVisibilityType('department')}
            disabled={saving}
          >
            부서 선택
          </Button>
        </div>

        {visibilityType === 'department' && (
          <div className="flex flex-wrap gap-2">
            {departments.map((dept) => (
              <Chip
                key={dept.departmentId}
                role="button"
                tabIndex={0}
                selected={departmentIds.includes(dept.departmentId)}
                onClick={() => !saving && toggleDepartment(dept.departmentId)}
                className="cursor-pointer"
              >
                {dept.name}
              </Chip>
            ))}
          </div>
        )}

        {invalid && <p className="text-xs text-rose-600">부서를 1개 이상 선택하세요</p>}

        {scopeChanged && (
          <p className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">
            공개 범위를 바꾸면 이 문서의 작업 묶음(scopeKey)이 달라져 다른 AI 작업으로 분리될 수 있습니다.
          </p>
        )}
      </div>
    </Modal>
  )
}
