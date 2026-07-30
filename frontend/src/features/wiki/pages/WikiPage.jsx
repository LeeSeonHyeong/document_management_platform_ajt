import { useNavigate, useParams } from 'react-router-dom'
import { EmptyState } from '@/components/ui'
import { useAuth } from '@/hooks/useAuth'
import { ROLES } from '@/shared/constants/enums'
import WikiNavSidebar from '../components/WikiNavSidebar'
import WikiDetail from '../components/WikiDetail'
import WikiAdminPanel from '../components/WikiAdminPanel'

// Figma 6R(관리자)·S2(사원) — Wiki 화면. 좌측 목차 사이드바 + 우측 상세.
// 레이아웃 컴포넌트(AppShell)는 A 담당이므로 여기서는 본문 영역만 구성한다.
export default function WikiPage() {
  const { wikiId } = useParams()
  const navigate = useNavigate()
  const { role } = useAuth()
  const isAdmin = role === ROLES.ADMIN

  return (
    <div className="flex min-h-[calc(100vh-124px)] items-stretch gap-3">
      <WikiNavSidebar selectedWikiId={wikiId} onSelectWiki={(id) => navigate(`/wiki/${id}`)} />
      <section className="flex min-w-0 flex-1 rounded-2xl border border-slate-200 bg-white p-5">
        {wikiId ? (
          <WikiDetail wikiId={wikiId} />
        ) : (
          <div className="flex flex-1 items-center justify-center">
            <EmptyState title="위키를 선택하세요" description="왼쪽 문서 목록에서 위키를 선택하면 내용을 볼 수 있습니다." />
          </div>
        )}
      </section>
      {wikiId && <WikiAdminPanel wikiId={wikiId} showEditor={isAdmin} />}
    </div>
  )
}
