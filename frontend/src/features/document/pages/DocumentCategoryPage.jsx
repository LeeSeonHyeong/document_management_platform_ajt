import { useState } from 'react'
import { Plus, Pencil, Trash2 } from 'lucide-react'
import { Button, Select, DataTable, EmptyState, ConfirmDialog, useToast } from '@/components/ui'
import { useWikiSpaces } from '@/features/wiki/queries'
import { useDocumentCategories, useDeleteDocumentCategory } from '../queries'
import DocumentCategoryFormModal from '../components/DocumentCategoryFormModal'

// Figma 4-8R — 원본문서 카테고리 관리. 카테고리는 Wiki 공간(scopeKey)별로 관리한다.
// (Wiki 카테고리는 에이전트가 자동 관리하며 여기서 편집하지 않는다 — FR-WIKI-014.)
export default function DocumentCategoryPage() {
  const toast = useToast()
  const [scopeKey, setScopeKey] = useState('')
  const [formState, setFormState] = useState(null) // { mode, category }
  const [deleting, setDeleting] = useState(null) // 삭제할 category

  const { data: wikiSpaces = [] } = useWikiSpaces()
  const { data: categories = [], isLoading } = useDocumentCategories(scopeKey)
  const deleteMutation = useDeleteDocumentCategory(scopeKey)

  function handleDelete() {
    deleteMutation.mutate(deleting.documentCategoryId, {
      onSuccess: () => setDeleting(null),
      onError: (e) => {
        if (e?.response?.status === 409) toast.error('문서에서 사용 중인 카테고리는 삭제할 수 없습니다.')
        else toast.error('삭제에 실패했습니다.')
        setDeleting(null)
      },
    })
  }

  const columns = [
    { key: 'name', header: '이름', render: (c) => c.name },
    { key: 'description', header: '설명', render: (c) => c.description || '-' },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: (c) => (
        <div className="flex justify-end gap-1">
          <Button size="sm" variant="ghost" onClick={() => setFormState({ mode: 'edit', category: c })}>
            <Pencil className="size-4" />
            수정
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setDeleting(c)}>
            <Trash2 className="size-4" />
            삭제
          </Button>
        </div>
      ),
    },
  ]

  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">카테고리 관리</h1>
        <Button variant="primary" disabled={!scopeKey} onClick={() => setFormState({ mode: 'create' })}>
          <Plus className="size-4" />
          카테고리 추가
        </Button>
      </div>

      <Select
        label="Wiki 공간"
        placeholder="공간을 선택하세요"
        value={scopeKey}
        onChange={(e) => setScopeKey(e.target.value)}
        options={wikiSpaces.map((space) => ({ value: space.scopeKey, label: space.displayName }))}
        className="max-w-xs"
      />

      {!scopeKey ? (
        <EmptyState title="Wiki 공간을 선택하세요" description="카테고리는 공간(scopeKey)별로 관리됩니다." />
      ) : (
        <DataTable
          columns={columns}
          rows={categories}
          rowKey="documentCategoryId"
          loading={isLoading}
          emptyState={<EmptyState title="카테고리가 없습니다" description="카테고리를 추가해보세요." />}
        />
      )}

      <DocumentCategoryFormModal
        open={Boolean(formState)}
        mode={formState?.mode}
        scopeKey={scopeKey}
        category={formState?.category}
        onClose={() => setFormState(null)}
        onSaved={() => setFormState(null)}
      />

      <ConfirmDialog
        open={Boolean(deleting)}
        onClose={() => setDeleting(null)}
        onConfirm={handleDelete}
        title="카테고리를 삭제할까요?"
        confirmLabel="삭제"
        tone="danger"
        loading={deleteMutation.isPending}
      >
        <p className="text-sm text-slate-600">
          "{deleting?.name}" 카테고리를 삭제합니다. 문서에서 사용 중인 카테고리는 삭제할 수 없습니다.
        </p>
      </ConfirmDialog>
    </section>
  )
}
