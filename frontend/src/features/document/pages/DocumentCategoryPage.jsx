import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ChevronLeft, Plus, Pencil, Trash2, Info } from 'lucide-react'
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
    <section className="space-y-5">
      <Link
        to="/admin/documents/source"
        className="focus-ring inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-semibold text-slate-500 hover:bg-slate-50"
      >
        <ChevronLeft className="size-4" />
        원본 문서
      </Link>

      <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
        <div className="flex flex-wrap items-start justify-between gap-4 px-5 py-4">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-lg font-bold text-slate-800">카테고리 현황</h2>
              <span className="rounded-full bg-primary-50 px-2 py-0.5 text-xs font-semibold text-primary-600">
                {categories.length}개
              </span>
            </div>
            <p className="mt-1 text-xs text-slate-400">
              원본 문서를 분류하는 체계입니다. 업로드 시 선택지로 노출됩니다.
            </p>
          </div>
          <Select
            placeholder="Wiki 공간 선택"
            value={scopeKey}
            onChange={(e) => setScopeKey(e.target.value)}
            options={wikiSpaces.map((space) => ({ value: space.scopeKey, label: space.displayName }))}
            className="w-56"
          />
        </div>

        <div className="mx-5 mb-4 flex items-center gap-3 rounded-xl border border-primary-200 bg-primary-50/40 p-3">
          <span className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-blue-500 to-violet-600 text-white">
            <Plus className="size-4" />
          </span>
          <span className="text-sm font-semibold text-slate-700">카테고리 추가</span>
          <span className="min-w-0 flex-1 text-xs text-slate-400">
            공간을 선택한 뒤 새 카테고리를 추가할 수 있습니다.
          </span>
          <Button variant="primary" disabled={!scopeKey} onClick={() => setFormState({ mode: 'create' })}>
            추가
          </Button>
        </div>

        {!scopeKey ? (
          <EmptyState title="Wiki 공간을 선택하세요" description="카테고리는 공간별로 관리됩니다." />
        ) : (
          <DataTable
            className="rounded-none border-x-0 border-b-0 shadow-none"
            columns={columns}
            rows={categories}
            rowKey="documentCategoryId"
            loading={isLoading}
            emptyState={<EmptyState title="카테고리가 없습니다" description="카테고리를 추가해보세요." />}
          />
        )}

        <div className="flex items-center gap-2 border-t border-slate-100 bg-primary-50/40 px-5 py-3 text-xs text-slate-500">
          <Info className="size-4 text-primary-500" />
          문서가 없는 카테고리만 삭제할 수 있습니다.
        </div>
      </div>

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
