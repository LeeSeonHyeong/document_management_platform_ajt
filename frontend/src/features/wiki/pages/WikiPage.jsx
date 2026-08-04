import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { EmptyState } from '@/components/ui'
import { useAuth } from '@/hooks/useAuth'
import { qk } from '@/shared/api/queryKeys'
import { fetchDepartments } from '@/api/departments'
import WikiNavSidebar from '../components/WikiNavSidebar'
import WikiDetail from '../components/WikiDetail'
import WikiAgentChat from '../components/WikiAgentChat'
import WikiArticleBar from '../components/WikiArticleBar'
import WikiEvidenceSections from '../components/WikiEvidenceSections'
import ColumnResizer from '../components/ColumnResizer'
import { CHAT, TREE, clampWidth, loadWidth, saveWidth } from '../panelWidths'
import { useWiki } from '../queries'
import { canUseWikiAgentChat } from '../agentChatAccess'

// Figma 6R(관리자)·S2(사원) — Wiki 화면.
//
// **열 배치의 근거.** 예전에는 전역 사이드바(240) + 문서 트리(176) + 본문 + 목차·근거(288)
// 네 열이었다. 내비게이션이 두 겹인 데다 1280px 화면에서 본문에 300px(30자)밖에 남지 않아
// 표가 찌그러졌다(실측). 그리고 이 화면의 주 기능인 AI 문서 편집이 우측 열의 **탭 하나 뒤에**
// 숨어 있어서, 그 탭을 켜면 목차·근거가 사라지는 — 서로 자리를 다투는 구조였다.
//
// 지금은 역할별로 자리를 나눈다:
//   - 전역 사이드바는 이 화면에서 56px 로 접힌다 (AppShell). 위키에 들어온 사람에게 모듈
//     전환기를 240px 펼쳐 둘 값이 낮다. 개념을 섞은 게 아니라 폭만 줄인 것이다.
//   - 우측 열은 **AI 문서 편집 전용**이다. 탭 전환이 없고 항상 보인다.
//   - 목차는 본문 위 sticky 바의 팝오버(WikiArticleBar), 출처 원본·관련 위키는 본문 끝
//     섹션(WikiEvidenceSections). 어느 폭에서도 접히지 않는다.
// 1280px 기준으로 본문에 약 550px 이 남는다 — 예전의 1.8배다.
//
// **좁아질 때 접히는 순서는 주 기능이 가장 늦다.**
//   1280px~     문서 트리 · 본문 · AI 편집 세 열
//   1024~1279   문서 트리 · 본문, AI 편집은 서랍
//   ~1023       본문만, 문서 트리와 AI 편집 둘 다 서랍
// 어느 폭에서 어떤 버튼이 뜨는지는 CSS(`lg:hidden`·`xl:hidden`)가 정한다.

// 열 사이 여백. 이 여백이 곧 폭 조절 손잡이(ColumnResizer)라서 컨테이너에 `gap` 을 주지
// 않는다 — 손잡이 옆에 또 간격이 붙으면 여백이 두 배로 벌어진다.
const RESIZER = 12

// 좁은 화면용 서랍. 화면을 덮되 바깥이나 Esc 로 닫힌다.
function Drawer({ open, onClose, side, title, width = 'w-[min(20rem,88vw)]', children }) {
  useEffect(() => {
    if (!open) return
    const onKey = (event) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null

  return (
    <div className="fixed inset-0 z-40">
      <button
        type="button"
        aria-label="닫기"
        onClick={onClose}
        className="absolute inset-0 bg-slate-900/30"
      />
      <div
        role="dialog"
        aria-label={title}
        className={`absolute inset-y-0 flex flex-col gap-3 bg-[#eef4ff] p-3 shadow-xl ${width} ${
          side === 'left' ? 'left-0' : 'right-0'
        }`}
      >
        <div className="flex shrink-0 items-center justify-between px-1">
          <span className="text-sm font-bold text-slate-800">{title}</span>
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            className="focus-ring rounded-lg p-1 text-slate-500 transition-colors hover:bg-white"
          >
            <X className="size-4" />
          </button>
        </div>
        <div className="flex min-h-0 flex-1 flex-col gap-3">{children}</div>
      </div>
    </div>
  )
}

export default function WikiPage() {
  const { wikiId } = useParams()
  const navigate = useNavigate()
  const { user, isSuperAdmin } = useAuth()

  // AI 수정 대화 패널 노출 판정(S15P11B106-229 후속).
  // 조회는 넓지만 수정 대화는 관리 권한이라, role=admin이 아니라 최고관리자 여부 + 담당 부서 단일 scope로 판단한다.
  const { data: wiki } = useWiki(wikiId)
  const departmentsQuery = useQuery({ queryKey: qk.departments.list, queryFn: fetchDepartments })
  // 로그인 사용자가 manager로 지정된 부서(= 관리 가능한 담당 부서). 없으면 null.
  const managedDepartment =
    (user?.userId &&
      (departmentsQuery.data ?? []).find(
        (department) => department.manager && String(department.manager.userId) === String(user.userId),
      )) ||
    null
  const canManageChat = canUseWikiAgentChat({
    isSuperAdmin,
    managedDepartmentId: managedDepartment?.departmentId ?? null,
    scopeKey: wiki?.scopeKey,
  })

  const [treeOpen, setTreeOpen] = useState(false)
  const [chatOpen, setChatOpen] = useState(false)

  // 열 폭은 사용자가 열 사이 여백을 끌어 정한다. 저장된 값으로 시작한다.
  const rowRef = useRef(null)
  const treeRef = useRef(null)
  const chatRef = useRef(null)
  const [treeWidth, setTreeWidth] = useState(() => loadWidth(TREE))
  const [chatWidth, setChatWidth] = useState(() => loadWidth(CHAT))

  // 이 열과 본문이 나눠 쓸 수 있는 폭. **반대쪽 열의 실제 렌더 폭을 읽는다** — 그 열이
  // 좁은 화면에서 숨어 있으면 `offsetWidth` 가 0 이라, 어느 폭에서 열이 서는지를 자바스크립트가
  // 다시 알 필요가 없다(CSS 와 브레이크포인트가 두 군데로 갈라지지 않는다).
  const availableFor = useCallback((otherRef) => {
    const row = rowRef.current
    if (!row) return undefined
    const other = otherRef.current?.offsetWidth ?? 0
    return row.clientWidth - other - (other > 0 ? RESIZER : 0) - RESIZER
  }, [])

  const resizeTree = useCallback(
    (next) => setTreeWidth(clampWidth(TREE, next, availableFor(chatRef))),
    [availableFor],
  )
  const resizeChat = useCallback(
    (next) => setChatWidth(clampWidth(CHAT, next, availableFor(treeRef))),
    [availableFor],
  )

  // 창이 좁아지면 저장된 폭이 본문 최소 폭을 침범할 수 있다. 그때는 줄이되 **저장하지는
  // 않는다** — 창을 다시 넓히면 사용자가 정한 폭으로 돌아와야 한다.
  useEffect(() => {
    const row = rowRef.current
    if (!row || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      setTreeWidth((current) => clampWidth(TREE, current, availableFor(chatRef)))
      setChatWidth((current) => clampWidth(CHAT, current, availableFor(treeRef)))
    })
    observer.observe(row)
    return () => observer.disconnect()
  }, [availableFor])

  // 위키를 바꾸면 서랍을 닫는다 — 열어둔 채로 두면 방금 고른 문서를 가린다.
  useEffect(() => {
    setTreeOpen(false)
    setChatOpen(false)
  }, [wikiId])

  const selectWiki = useCallback(
    (id) => {
      navigate(`/wiki/${id}`)
      setTreeOpen(false)
    },
    [navigate],
  )

  const tree = <WikiNavSidebar selectedWikiId={wikiId} onSelectWiki={selectWiki} />
  const chat = wikiId && canManageChat ? <WikiAgentChat wikiId={wikiId} /> : null

  return (
    <div ref={rowRef} className="flex h-full min-h-0 items-stretch pt-3">
      {/* 문서 트리: 1024px 이상에서 열로 선다 */}
      <div ref={treeRef} className="hidden shrink-0 lg:flex" style={{ width: treeWidth }}>
        {tree}
      </div>
      <ColumnResizer
        label="문서 목록 폭"
        value={treeWidth}
        min={TREE.min}
        max={TREE.max}
        onChange={resizeTree}
        onCommit={() => saveWidth(TREE, treeWidth)}
        onReset={() => {
          resizeTree(TREE.default)
          saveWidth(TREE, TREE.default)
        }}
        className="hidden lg:flex"
      />

      <section className="min-w-0 flex-1 overflow-y-auto rounded-2xl border border-slate-200 bg-white px-5 pb-6 sm:px-6">
        {wikiId ? (
          <>
            <WikiArticleBar
              wikiId={wikiId}
              onOpenTree={() => setTreeOpen(true)}
              treeButtonClassName="lg:hidden"
              onOpenChat={chat ? () => setChatOpen(true) : undefined}
              chatButtonClassName="xl:hidden"
            />
            <WikiDetail wikiId={wikiId} />
            <WikiEvidenceSections wikiId={wikiId} />
          </>
        ) : (
          <div className="flex h-full items-center justify-center">
            <EmptyState
              title="위키를 선택하세요"
              description="문서 목록에서 위키를 선택하면 내용을 볼 수 있습니다."
            />
          </div>
        )}
      </section>

      {/* AI 문서 편집: 1280px 이상에서 열로 선다 */}
      {chat && (
        <>
          <ColumnResizer
            label="AI 문서 편집 폭"
            value={chatWidth}
            min={CHAT.min}
            max={CHAT.max}
            invert
            onChange={resizeChat}
            onCommit={() => saveWidth(CHAT, chatWidth)}
            onReset={() => {
              resizeChat(CHAT.default)
              saveWidth(CHAT, CHAT.default)
            }}
            className="hidden xl:flex"
          />
          <div ref={chatRef} className="hidden shrink-0 xl:flex" style={{ width: chatWidth }}>
            {chat}
          </div>
        </>
      )}

      <Drawer open={treeOpen} onClose={() => setTreeOpen(false)} side="left" title="문서 목록">
        {tree}
      </Drawer>
      <Drawer
        open={chatOpen}
        onClose={() => setChatOpen(false)}
        side="right"
        title="AI 문서 편집"
        width="w-[min(26rem,92vw)]"
      >
        {chat}
      </Drawer>
    </div>
  )
}
