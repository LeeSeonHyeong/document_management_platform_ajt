import { useState } from 'react'
import { Link } from 'react-router-dom'

function WikiTitle({ wiki }) {
  if (wiki.deleted) {
    return (
      <span className="inline-flex min-w-0 items-center gap-1.5 text-slate-600">
        <span className="truncate">{wiki.title}</span>
        <span className="shrink-0 rounded-full bg-rose-50 px-1.5 py-0.5 text-[10px] font-semibold text-rose-700">
          삭제됨
        </span>
      </span>
    )
  }
  return (
    <Link
      to={`/wiki/${wiki.wikiId}`}
      className="focus-ring min-w-0 truncate rounded font-semibold text-primary-600 underline-offset-2 hover:underline"
    >
      {wiki.title}
    </Link>
  )
}

export function AffectedWikiContent({ wikis = [], expanded, onToggle }) {
  if (wikis.length === 0) {
    return <span className="flex justify-center text-slate-400">-</span>
  }

  const first = wikis[0]
  const remainingCount = wikis.length - 1
  return (
    <div className="min-w-0 text-center">
      <div className="flex min-w-0 items-center justify-center gap-2">
        <WikiTitle wiki={first} />
        {remainingCount > 0 && (
          <button
            type="button"
            aria-expanded={expanded}
            onClick={onToggle}
            className="focus-ring shrink-0 rounded-full bg-slate-100 px-2 py-0.5 text-xs font-semibold text-slate-600 hover:bg-slate-200"
          >
            외 {remainingCount}건
          </button>
        )}
      </div>

      {expanded && remainingCount > 0 && (
        <ul className="mt-2 space-y-1 rounded-lg border border-slate-200 bg-slate-50 p-2 text-left">
          {wikis.map((wiki) => (
            <li key={`${wiki.wikiId}-${wiki.deleted ? 'deleted' : 'live'}`} className="flex min-w-0 text-xs">
              <WikiTitle wiki={wiki} />
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default function AffectedWikiDisplay({ wikis }) {
  const [expanded, setExpanded] = useState(false)
  return (
    <AffectedWikiContent
      wikis={wikis}
      expanded={expanded}
      onToggle={() => setExpanded((open) => !open)}
    />
  )
}
