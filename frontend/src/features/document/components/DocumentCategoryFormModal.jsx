import { useEffect, useState } from 'react'
import { Modal, Button, Input, Textarea, useToast } from '@/components/ui'
import { useCreateDocumentCategory, useUpdateDocumentCategory } from '../queries'

// Figma 4-8-1R — 원본문서 카테고리 추가/수정 (모달 하나로 모드만 다름).
// 추가: POST { scopeKey, name, description } · 수정: PATCH { name, description } (수정에는 scopeKey를 보내지 않는다).
export default function DocumentCategoryFormModal({ open, mode, scopeKey, category, onClose, onSaved }) {
  const toast = useToast()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const createMutation = useCreateDocumentCategory()
  const updateMutation = useUpdateDocumentCategory(scopeKey)
  const saving = createMutation.isPending || updateMutation.isPending

  useEffect(() => {
    if (!open) return
    setName(mode === 'edit' ? (category?.name ?? '') : '')
    setDescription(mode === 'edit' ? (category?.description ?? '') : '')
  }, [open, mode, category])

  function handleSave() {
    const trimmed = name.trim()
    if (!trimmed || saving) return

    const onSuccess = () => {
      onSaved?.()
      onClose?.()
    }
    const onError = (e) => {
      if (e?.response?.status === 409) toast.error('같은 공간에 동일한 이름의 카테고리가 있습니다.')
      else toast.error('저장에 실패했습니다.')
    }

    if (mode === 'edit') {
      updateMutation.mutate({ categoryId: category.documentCategoryId, name: trimmed, description }, { onSuccess, onError })
    } else {
      createMutation.mutate({ scopeKey, name: trimmed, description }, { onSuccess, onError })
    }
  }

  return (
    <Modal
      open={open}
      onClose={saving ? undefined : onClose}
      closeOnOverlay={!saving}
      title={mode === 'edit' ? '카테고리 수정' : '카테고리 추가'}
      size="md"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={saving}>
            취소
          </Button>
          <Button variant="primary" onClick={handleSave} loading={saving} disabled={!name.trim()}>
            저장
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Input label="이름" required value={name} onChange={(e) => setName(e.target.value)} placeholder="카테고리 이름" />
        <Textarea label="설명" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="설명 (선택)" rows={3} />
      </div>
    </Modal>
  )
}
