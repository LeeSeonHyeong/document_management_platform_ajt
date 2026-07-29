import { useEffect, useState } from 'react'
import { ChevronRight, ChevronDown } from 'lucide-react'
import { Select, SearchBar, Spinner } from '@/components/ui'
import { cn } from '@/shared/lib/cn'
import { useWikiSpaces, useWikiCategories, useWikis } from '../queries'

// Figma 6R 좌측 (Jira -85) — Wiki 목차 사이드바.
// 공간(scopeKey) → 카테고리 → Wiki 트리. 키워드 검색 시 평면 결과 목록으로 전환한다.
export default function WikiNavSidebar({ selectedWikiId, onSelectWiki }) {
  const [scopeKey, setScopeKey] = useState('')
  const [keyword, setKeyword] = useState('')
  const [expanded, setExpanded] = useState(() => new Set())

  const { data: spaces = [] } = useWikiSpaces()
  const { data: categories = [] } = useWikiCategories(scopeKey)
  // 검색 중이 아니면 스코프 전체 Wiki를 받아 카테고리별로 묶는다. 검색 중이면 keyword 필터 결과(평면).
  const { data: wikiPage, isLoading } = useWikis(
    scopeKey ? { scopeKey, keyword: keyword || undefined, size: 200 } : undefined,
  )
  const wikis = wikiPage?.items ?? []

  // 접근 가능한 첫 공간을 기본 선택한다.
  useEffect(() => {
    if (!scopeKey && spaces.length) setScopeKey(spaces[0].scopeKey)
  }, [scopeKey, spaces])

  function toggleCategory(categoryId) {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(categoryId)) next.delete(categoryId)
      else next.add(categoryId)
      return next
    })
  }

  const searching = keyword.trim().length > 0

  return (
    <aside className="flex w-72 shrink-0 flex-col gap-3 border-r border-slate-200 pr-4">
      <Select
        value={scopeKey}
        onChange={(e) => setScopeKey(e.target.value)}
        placeholder="Wiki 공간 선택"
        options={spaces.map((s) => ({ value: s.scopeKey, label: `${s.displayName} (${s.wikiCount})` }))}
      />

      <SearchBar placeholder="Wiki 검색" defaultValue={keyword} onSearch={setKeyword} />

      {isLoading ? (
        <div className="flex justify-center py-8">
          <Spinner size="sm" />
        </div>
      ) : searching ? (
        // 검색 결과: 카테고리 트리 대신 평면 목록
        <ul className="space-y-0.5">
          {wikis.length === 0 && <li className="px-2 py-1 text-sm text-slate-400">검색 결과가 없습니다.</li>}
          {wikis.map((w) => (
            <WikiItem key={w.wikiId} wiki={w} active={w.wikiId === selectedWikiId} onSelect={onSelectWiki} />
          ))}
        </ul>
      ) : (
        // 공간 → 카테고리 → Wiki 트리
        <ul className="space-y-1">
          {categories.map((cat) => {
            const open = expanded.has(cat.wikiCategoryId)
            const catWikis = wikis.filter((w) => w.wikiCategoryId === cat.wikiCategoryId)
            return (
              <li key={cat.wikiCategoryId}>
                <button
                  type="button"
                  onClick={() => toggleCategory(cat.wikiCategoryId)}
                  className="focus-ring flex w-full items-center gap-1 rounded-md px-2 py-1.5 text-left text-sm font-medium text-slate-700 hover:bg-slate-50"
                >
                  {open ? <ChevronDown className="size-4 text-slate-400" /> : <ChevronRight className="size-4 text-slate-400" />}
                  {cat.name}
                  <span className="ml-auto text-xs text-slate-400">{catWikis.length}</span>
                </button>
                {open && (
                  <ul className="mt-0.5 space-y-0.5 pl-5">
                    {catWikis.length === 0 && <li className="px-2 py-1 text-xs text-slate-400">Wiki 없음</li>}
                    {catWikis.map((w) => (
                      <WikiItem key={w.wikiId} wiki={w} active={w.wikiId === selectedWikiId} onSelect={onSelectWiki} />
                    ))}
                  </ul>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </aside>
  )
}

function WikiItem({ wiki, active, onSelect }) {
  return (
    <li>
      <button
        type="button"
        onClick={() => onSelect(wiki.wikiId)}
        className={cn(
          'focus-ring w-full truncate rounded-md px-2 py-1.5 text-left text-sm',
          active ? 'bg-primary-50 font-medium text-primary-700' : 'text-slate-600 hover:bg-slate-50',
        )}
      >
        {wiki.title}
      </button>
    </li>
  )
}
