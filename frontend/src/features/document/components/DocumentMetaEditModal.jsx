import { useEffect, useState } from 'react'
import { Modal, Button, Select, Chip } from '@/components/ui'
import { useDepartments } from '@/features/department/queries'
import { useDocumentCategories, useUpdateDocument } from '../queries'
import { buildScopeKey } from '../scope'

// 원본문서 상세(4-7-1R)의 "메타 수정" 액션. 카테고리와 공개 범위를 함께 수정한다.
// PATCH /documents/:id 는 departmentIds 를 배열로 받는다. 공개 범위가 바뀌면 재처리가 일어난다.
export default function DocumentMetaEditModal({ open, onClose, doc, onSaved }) {
  const { data: departments = [] } = useDepartments()
  const updateMutation = useUpdateDocument(doc?.documentId)

  const [visibilityType, setVisibilityType] = useState('all')
  const [departmentIds, setDepartmentIds] = useState([])
  const [documentCategoryId, setDocumentCategoryId] = useState('')

  useEffect(() => {
    if (!open || !doc) return
    setVisibilityType(doc.visibilityType ?? 'all')
    setDepartmentIds((doc.departments ?? []).map((d) => d.departmentId))
    setDocumentCategoryId(doc.documentCategoryId ?? '')
  }, [open, doc])

  const scopeKey = buildScopeKey(visibilityType, departmentIds)
  const { data: categories = [] } = useDocumentCategories(scopeKey)
  const saving = updateMutation.isPending

  const invalidDept = visibilityType === 'department' && departmentIds.length === 0
  const canSave = Boolean(documentCategoryId) && !invalidDept && !saving

  // 공개 범위가 바뀌면 scopeKey가 달라져 카테고리 목록도 달라진다 → 카테고리 선택 초기화.
  function setVisibility(next) {
    setVisibilityType(next)
    if (next === 'all') setDepartmentIds([])
    setDocumentCategoryId('')
  }
  function toggleDepartment(id) {
    setDepartmentIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
    setDocumentCategoryId('')
  }

  function handleSave() {
    if (!canSave) return
    updateMutation.mutate(
      { documentCategoryId, visibilityType, departmentIds },
      {
        onSuccess: () => {
          onSaved?.()
          onClose?.()
        },
      },
    )
  }

  const scopeChanged = Boolean(doc) && scopeKey !== doc.scopeKey

  return (
    <Modal
      open={open}
      onClose={saving ? undefined : onClose}
      closeOnOverlay={!saving}
      title="메타데이터 수정"
      description="카테고리와 공개 범위를 수정합니다."
      size="md"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={saving}>
            취소
          </Button>
          <Button variant="primary" onClick={handleSave} loading={saving} disabled={!canSave}>
            저장
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="space-y-2">
          <p className="text-sm font-medium text-slate-700">공개 범위</p>
          <div className="flex gap-2">
            <Button size="sm" variant={visibilityType === 'all' ? 'primary' : 'outline'} onClick={() => setVisibility('all')} disabled={saving}>
              전체
            </Button>
            <Button size="sm" variant={visibilityType === 'department' ? 'primary' : 'outline'} onClick={() => setVisibility('department')} disabled={saving}>
              부서 선택
            </Button>
          </div>
          {visibilityType === 'department' && (
            <div className="flex flex-wrap gap-2 pt-1">
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
          {invalidDept && <p className="text-xs text-rose-600">부서를 1개 이상 선택하세요</p>}
        </div>

        <Select
          label="카테고리"
          required
          placeholder={scopeKey ? '카테고리 선택' : '공개 범위를 먼저 선택하세요'}
          disabled={!scopeKey || saving}
          value={documentCategoryId}
          onChange={(e) => setDocumentCategoryId(e.target.value)}
          options={categories.map((c) => ({ value: c.documentCategoryId, label: c.name }))}
        />

        {scopeChanged && (
          <p className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">
            공개 범위를 바꾸면 이 문서가 다른 작업 묶음(scopeKey)으로 이동하며 재처리가 일어납니다.
          </p>
        )}
      </div>
    </Modal>
  )
}
