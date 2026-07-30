import { useEffect, useRef, useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { SearchBar, Spinner } from '@/components/ui'
import { cn } from '@/shared/lib/cn'
import { useWikiCategories, useWikis, useWikiSpaces } from '../queries'

export default function WikiNavSidebar({ selectedWikiId, onSelectWiki }) {
  const [scopeKey, setScopeKey] = useState('')
  const [keyword, setKeyword] = useState('')
  const [expanded, setExpanded] = useState(() => new Set())
  const initializedScope = useRef('')

  const { data: spaces = [] } = useWikiSpaces()
  const { data: categories = [] } = useWikiCategories(scopeKey)
  const { data: wikiPage, isLoading } = useWikis(
    scopeKey ? { scopeKey, keyword: keyword || undefined, size: 200 } : undefined,
  )
  const wikis = wikiPage?.items ?? []
  const firstWikiId = wikis[0]?.wikiId
  const searching = keyword.trim().length > 0

  useEffect(() => {
    if (!scopeKey && spaces.length) setScopeKey(spaces[0].scopeKey)
  }, [scopeKey, spaces])

  useEffect(() => {
    if (!scopeKey || !categories.length || initializedScope.current === scopeKey) return
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
    <aside className="flex w-56 shrink-0 flex-col rounded-2xl border border-slate-200 bg-white p-3">
      <div className="flex items-center justify-between px-2 py-2">
        <h2 className="text-base font-bold text-slate-800">문서 목록</h2>
        <span className="text-xs font-semibold text-slate-400">
          {wikiPage?.totalCount ?? wikis.length}
        </span>
      </div>

      <SearchBar
        placeholder="문서명으로 검색"
        defaultValue={keyword}
        onSearch={setKeyword}
        className="mb-2"
      />

      <div className="mb-2 flex gap-1.5">
        <span className="rounded-full border border-primary-200 bg-primary-50 px-2.5 py-1 text-[11px] font-semibold text-primary-600">
          전체
        </span>
        <span className="rounded-full border border-slate-200 px-2.5 py-1 text-[11px] text-slate-400">문서</span>
        <span className="rounded-full border border-slate-200 px-2.5 py-1 text-[11px] text-slate-400">위키</span>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {isLoading ? (
          <div className="flex justify-center py-8">
            <Spinner size="sm" />
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
