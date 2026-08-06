import { useState } from 'react'
import { Link } from 'react-router-dom'
import { BookOpen, FileText } from 'lucide-react'
import { useWiki } from '../queries'
import WikiSourcePreviewModal from './WikiSourcePreviewModal'

// 출처 원본·관련 위키를 **본문 뒤에 이어지는 섹션**으로 보여준다.
//
// 예전에는 이 둘이 우측 열의 카드였고, 그 열은 AI 편집 탭과 자리를 다퉜다. 좁은 화면에서는
// 열째 접혀서 근거가 통째로 사라졌다 — 근거 표시가 이 제품의 핵심인데 버튼 뒤로 숨은 것이다.
//
// 지금은 본문 다음 자리에 있다. 정보 구조로도 그게 맞다: 각주가 본문 끝에 오는 것과 같은
// 이유로, "이 문서의 근거"는 문서를 읽은 뒤에 온다. 어떤 폭에서도 접히지 않는다.
export default function WikiEvidenceSections({ wikiId }) {
  const [previewDoc, setPreviewDoc] = useState(null)
  const { data: wiki } = useWiki(wikiId)

  const sources = wiki?.evidenceDocuments ?? []
  const related = wiki?.relatedWikis ?? []

  // 둘 다 비면 아무것도 붙이지 않는다 — 빈 카드 두 개가 본문 끝에 남는 것보다 없는 편이 낫다.
  if (!sources.length && !related.length) return null

  return (
    <>
      <section className="mt-10 border-t border-slate-200 pt-6">
        <h2 className="mb-3 text-xs font-semibold tracking-wide text-slate-400">
          근거와 관련 문서
        </h2>
        <div className="grid gap-3 sm:grid-cols-2">
          {sources.length > 0 && (
            <EvidenceCard
              title="출처 원본"
              subtitle="이 위키를 생성할 때 참고한 원본 문서"
              count={sources.length}
            >
              <ul className="space-y-2">
                {sources.map((document) => (
                  <li key={document.documentId}>
                    <button
                      type="button"
                      onClick={() => setPreviewDoc(document)}
                      className="focus-ring flex w-full items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-3 text-left text-xs text-slate-500 transition-colors hover:border-primary-200 hover:bg-primary-50"
                    >
                      <span className="flex size-6 shrink-0 items-center justify-center rounded-lg bg-primary-50 text-primary-500">
                        <FileText className="size-3.5" />
                      </span>
                      <span className="min-w-0 flex-1 truncate">{document.originalFileName}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </EvidenceCard>
          )}

          {/* 두 카드는 나란히 서므로 머리(제목+설명)와 줄 높이를 맞춘다. 한쪽에만 설명이 있거나
              한쪽에만 아이콘이 있으면 목록 시작 높이와 줄 높이가 어긋나 흐트러져 보인다. */}
          {related.length > 0 && (
            <EvidenceCard
              title="관련 위키"
              subtitle="이 위키와 서로 연결된 다른 위키"
              count={related.length}
            >
              <ul className="space-y-2">
                {related.map((relatedWiki) => (
                  <li key={relatedWiki.wikiId}>
                    <Link
                      to={`/wiki/${relatedWiki.wikiId}`}
                      className="focus-ring flex w-full items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-3 text-xs font-medium text-slate-600 transition-colors hover:border-violet-200 hover:bg-violet-50 hover:text-violet-600"
                    >
                      <span className="flex size-6 shrink-0 items-center justify-center rounded-lg bg-violet-50 text-violet-500">
                        <BookOpen className="size-3.5" />
                      </span>
                      <span className="min-w-0 flex-1 truncate">{relatedWiki.title}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            </EvidenceCard>
          )}
        </div>
      </section>

      <WikiSourcePreviewModal
        open={Boolean(previewDoc)}
        evidenceDocument={previewDoc}
        onClose={() => setPreviewDoc(null)}
      />
    </>
  )
}

// 본문 안에 있으므로 카드는 테두리만 두고 배경을 흰색으로 두지 않는다 — 흰 본문 위 흰 카드는
// 경계가 테두리 하나뿐이라 떠 보이지 않는다. 높이는 내용이 정하고, 안쪽 스크롤은 걸지
// 않는다(페이지가 스크롤하는데 카드도 스크롤하면 스크롤이 두 겹이 된다).
function EvidenceCard({ title, subtitle, count, children }) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-slate-50/50 p-4">
      <div className="mb-3">
        <div className="flex items-center justify-between">
          <h3 className="text-[15px] font-bold text-slate-800">{title}</h3>
          <span className="text-xs font-semibold text-slate-400">{count}</span>
        </div>
        {subtitle && <p className="mt-1 text-xs text-slate-400">{subtitle}</p>}
      </div>
      {children}
    </section>
  )
}
