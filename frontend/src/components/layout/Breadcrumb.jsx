import { Fragment } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ChevronRight } from 'lucide-react'
import { ROUTE_LABELS } from './navConfig'

// 현재 경로에서 브레드크럼을 자동 생성한다. 라벨은 ROUTE_LABELS 매핑을 따르고,
// 매핑에 없는 세그먼트(:id 등)는 그대로 보여준다.
function buildCrumbs(pathname) {
  if (pathname === '/') return [{ to: '/', label: ROUTE_LABELS['/'] }]
  const segments = pathname.split('/').filter(Boolean)
  const crumbs = [{ to: '/', label: ROUTE_LABELS['/'] }]
  let acc = ''
  for (const seg of segments) {
    acc += `/${seg}`
    crumbs.push({ to: acc, label: ROUTE_LABELS[acc] ?? decodeURIComponent(seg) })
  }
  return crumbs
}

export default function Breadcrumb() {
  const { pathname } = useLocation()
  const crumbs = buildCrumbs(pathname)

  return (
    <nav aria-label="브레드크럼" className="flex items-center gap-1 text-sm">
      {crumbs.map((crumb, i) => {
        const last = i === crumbs.length - 1
        return (
          <Fragment key={crumb.to}>
            {i > 0 && <ChevronRight className="size-4 text-slate-300" />}
            {last ? (
              <span className="font-medium text-slate-700">{crumb.label}</span>
            ) : (
              <Link to={crumb.to} className="text-slate-400 hover:text-slate-600">
                {crumb.label}
              </Link>
            )}
          </Fragment>
        )
      })}
    </nav>
  )
}
