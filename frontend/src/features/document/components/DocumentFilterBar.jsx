import { FilterBar, SearchBar, Select, Input } from '@/components/ui'
import { FILE_ACCEPT } from '@/shared/constants/enums'
import { useWikiSpaces } from '@/features/wiki/queries'
import { useDocumentCategories } from '../queries'

const STATUS_OPTIONS = [
  { value: 'uploaded', label: '업로드 완료' },
  { value: 'parsing', label: '파싱 중' },
  { value: 'processing', label: '처리 중' },
  { value: 'completed', label: '처리 완료' },
  { value: 'failed', label: '실패' },
  { value: 'cancelled', label: '취소' },
]

const FILE_TYPE_OPTIONS = FILE_ACCEPT.WIKI_SOURCE.map((ext) => ({ value: ext, label: ext.toUpperCase() }))

// DocumentListPage(4R)와 SourceDocumentListPage(4-7R)가 공유하는 필터.
// shared/hooks에 usePagedQuery가 아직 없어 URL 동기화는 각 페이지가 useSearchParams로 직접 한다.
export default function DocumentFilterBar({ filters, onChange }) {
  const { data: wikiSpaces = [] } = useWikiSpaces()
  const { data: categories = [] } = useDocumentCategories(filters.scopeKey)

  const set = (patch) => onChange(patch)

  return (
    <FilterBar className="mb-4">
      <SearchBar
        placeholder="파일명 검색"
        defaultValue={filters.keyword ?? ''}
        onSearch={(keyword) => set({ keyword })}
        className="w-64"
      />
      <Select
        placeholder="Wiki 공간"
        value={filters.scopeKey ?? ''}
        onChange={(e) => set({ scopeKey: e.target.value || undefined, categoryId: undefined })}
        options={wikiSpaces.map((space) => ({ value: space.scopeKey, label: space.displayName }))}
      />
      <Select
        placeholder="카테고리"
        value={filters.categoryId ?? ''}
        onChange={(e) => set({ categoryId: e.target.value || undefined })}
        options={categories.map((c) => ({ value: c.documentCategoryId, label: c.name }))}
        disabled={!filters.scopeKey}
      />
      <Select
        placeholder="처리 상태"
        value={filters.status ?? ''}
        onChange={(e) => set({ status: e.target.value || undefined })}
        options={STATUS_OPTIONS}
      />
      <Select
        placeholder="파일 형식"
        value={filters.fileType ?? ''}
        onChange={(e) => set({ fileType: e.target.value || undefined })}
        options={FILE_TYPE_OPTIONS}
      />
      <Input
        type="date"
        value={filters.uploadedFrom ?? ''}
        onChange={(e) => set({ uploadedFrom: e.target.value || undefined })}
        className="w-40"
      />
      <span className="text-slate-400">~</span>
      <Input
        type="date"
        value={filters.uploadedTo ?? ''}
        onChange={(e) => set({ uploadedTo: e.target.value || undefined })}
        className="w-40"
      />
    </FilterBar>
  )
}
