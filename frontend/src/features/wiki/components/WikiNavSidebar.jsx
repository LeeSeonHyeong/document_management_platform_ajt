import { useEffect, useMemo, useRef, useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { SearchBar, Select } from '@/components/ui'
import { cn } from '@/shared/lib/cn'
import { useWikiCategories, useWikis, useWikiSpaces } from '../queries'

export default function WikiNavSidebar({ selectedWikiId, onSelectWiki }) {
  // scopeKey === '' 이면 전체 부서(스코프 필터 없이 조회).
  const [scopeKey, setScopeKey] = useState('')
  const [keyword, setKeyword] = useState('')
  const [expanded, setExpanded] = useState(() => new Set())
  const initializedScope = useRef(null)

  const { data: spaces = [] } = useWikiSpaces()
  const { data: scopeCategories = [] } = useWikiCategories(scopeKey)
  const filters = useMemo(
    () => ({ scopeKey: scopeKey || undefined, keyword: keyword || undefined, size: 100 }),
    [scopeKey, keyword],
  )
  const { data: wikiPage, isLoading } = useWikis(filters)
  const wikis = wikiPage?.items ?? []
  const firstWikiId = wikis[0]?.wikiId
  const searching = keyword.trim().length > 0
  const selectedSpace = spaces.find((space) => space.scopeKey === scopeKey)

  // /wiki-categories 는 scopeKey 가 필수라 전체 부서에서는 호출할 수 없다.
  // 이때는 목록 응답의 wikiCategoryId·wikiCategoryName 으로 트리를 구성한다.
  const listCategories = useMemo(() => {
    const map = new Map()
    ;(wikiPage?.items ?? []).forEach((wiki) => {
      const id = String(wiki.wikiCategoryId)
      if (!map.has(id)) {
        map.set(id, { wikiCategoryId: wiki.wikiCategoryId, name: wiki.wikiCategoryName })
      }
    })
    return [...map.values()]
  }, [wikiPage])
  const categories = scopeKey ? scopeCategories : listCategories

  useEffect(() => {
    if (!categories.length || initializedScope.current === scopeKey) return
    setExpanded(new Set(categories.map((category) => category.wikiCategoryId)))
    initializedScope.current = scopeKey
  }, [categories, scopeKey])

  useEffect(() => {
    if (!selectedWikiId && !searching && firstWikiId) {
      onSelectWiki(firstWikiId)
    }
  }, [firstWikiId, onSelectWiki, searching, selectedWikiId])

  function toggleCategory(categoryId) {
    setExpanded((previous) => {
      const next = new Set(previous)
      if (next.has(categoryId)) next.delete(categoryId)
      else next.add(categoryId)
      return next
    })
  }

  return (
    <aside
      className="flex h-full w-full min-h-0 flex-col rounded-2xl border border-slate-200 bg-white p-3"
    >
      <div className="flex items-center justify-between px-2 py-2">
        <h2 className="text-base font-bold text-slate-800">부서</h2>
        <span className="text-xs font-semibold text-slate-400">{spaces.length}</span>
      </div>

      <Select
        aria-label="부서 스코프"
        value={scopeKey}
        onChange={(event) => setScopeKey(event.target.value)}
        className="text-xs"
      >
        <option value="">전체 부서</option>
        {spaces.map((space) => (
          <option key={space.scopeKey} value={space.scopeKey}>
            {space.displayName}
          </option>
        ))}
      </Select>

      <div className="mt-3 flex items-center justify-between border-t border-slate-100 px-2 pt-3 pb-2">
        <h2 className="text-base font-bold text-slate-800">위키 목록</h2>
        <span className="text-xs font-semibold text-slate-400">
          {wikiPage?.totalCount ?? wikis.length}
        </span>
      </div>

      <SearchBar
        placeholder="위키 문서명으로 검색"
        defaultValue={keyword}
        onSearch={setKeyword}
        className="mb-2"
      />

      <div className="thin-scroll min-h-0 flex-1 overflow-y-auto">
        {isLoading ? (
          <div className="animate-pulse space-y-1 px-2 py-1">
            {Array.from({ length: 7 }, (_, index) => (
              <div key={index} className="h-8 rounded-lg bg-slate-100" style={{ width: `${85 - (index % 3) * 12}%` }} />
            ))}
          </div>
        ) : searching ? (
          <ul className="space-y-1">
            {wikis.length === 0 && (
              <li className="px-2 py-8 text-center text-sm text-slate-400">검색 결과가 없습니다.</li>
            )}
            {wikis.map((wiki) => (
              <WikiItem
                key={wiki.wikiId}
                wiki={wiki}
                active={String(wiki.wikiId) === String(selectedWikiId)}
                onSelect={onSelectWiki}
              />
            ))}
          </ul>
        ) : (
          <div className="space-y-1">
            <div className="flex items-center gap-2 rounded-lg px-2 py-2 text-sm font-semibold text-slate-600">
              <ChevronDown className="size-4 text-slate-400" />
              위키
            </div>
            <ul className="space-y-1 pl-3">
              {categories.map((category) => {
                const open = expanded.has(category.wikiCategoryId)
                const categoryWikis = wikis.filter(
                  (wiki) => String(wiki.wikiCategoryId) === String(category.wikiCategoryId),
                )
                return (
                  <li key={category.wikiCategoryId}>
                    <button
                      type="button"
                      onClick={() => toggleCategory(category.wikiCategoryId)}
                      className="focus-ring flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left text-sm text-slate-600 hover:bg-slate-50"
                    >
                      {open ? (
                        <ChevronDown className="size-4 shrink-0 text-slate-400" />
                      ) : (
                        <ChevronRight className="size-4 shrink-0 text-slate-400" />
                      )}
                      <span className="truncate">{category.name}</span>
                      <span className="ml-auto text-xs text-slate-400">{categoryWikis.length}</span>
                    </button>
                    {open && (
                      <ul className="space-y-0.5 pl-5">
                        {categoryWikis.map((wiki) => (
                          <WikiItem
                            key={wiki.wikiId}
                            wiki={wiki}
                            active={String(wiki.wikiId) === String(selectedWikiId)}
                            onSelect={onSelectWiki}
                          />
                        ))}
                      </ul>
                    )}
                  </li>
                )
              })}
            </ul>
          </div>
        )}
      </div>

      {selectedSpace && (
        <p className="mt-2 px-2 text-[11px] leading-4 text-slate-400">
          {selectedSpace.displayName}에 공개된 위키만 표시됩니다
        </p>
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
          'focus-ring flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm transition-colors',
          active
            ? 'bg-primary-50 font-semibold text-primary-600'
            : 'text-slate-500 hover:bg-slate-50 hover:text-slate-700',
        )}
      >
        <span className={cn('size-1.5 shrink-0 rounded-full', active ? 'bg-primary-500' : 'bg-slate-300')} />
        <span className="truncate">{wiki.title}</span>
      </button>
    </li>
  )
}
