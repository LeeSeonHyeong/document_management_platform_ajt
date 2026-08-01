"""가짜 조회 API — 계약의 「Wiki 조회 API」 8개와 「일정 조회 API」 2개를 흉내 낸다.

**이 파일은 폐기 대상이다.** 백엔드가 실제 조회 API 를 만들면 사라진다. `src/` 아래 어느
파일도 이것을 임포트하지 않는다.

증명하는 것과 못 하는 것을 구분해 둔다. 이 게이트웨이는 **우리 주문서에 앞뒤가
안 맞는 게 없다**를 보인다. 우리가 상상한 대로 답하기 때문이다. **백엔드가 실제로
이렇게 만들 수 있다**는 증명하지 못한다 — 그것은 백엔드 검토에서만 확인된다.

`wikiId` 는 파일명 순서로 1부터 붙인다. 진짜 백엔드는 DB 시퀀스를 쓴다. 이 차이가
`wikiPath` 없이는 주소를 복원할 수 없다는 것을 그대로 재현한다 (설계 4절).

검색은 2글자 부분문자열 포함으로 한다. MySQL `ngram_token_size=2` 와 같은 낟알이라
한국어 조사를 뚫는다 — 실측 근거는 `experiments/INDEX.md`.

404 본문의 `code` 는 어디서나 `WIKI_NOT_FOUND` 하나로 고정돼 있다. 계약은 조회 API 별로
`WIKI_SCOPE_NOT_FOUND`·`DOCUMENT_NOT_FOUND` 등을 구분하지만, 이 게이트웨이는 그
구분을 재현하지 않는다 — 클라이언트가 `WIKI_CAPABILITY_EXPIRED` 만 특별 취급하고
나머지는 전부 하나로 묶어 처리하기 때문에 어댑터 동작에는 영향이 없다. 하지만 이
파일의 `code` 값은 실제 백엔드와 다르다. 허가 만료(`WIKI_CAPABILITY_EXPIRED`) 분기도
이 게이트웨이로는 재현하지 못한다 — 이 파일을 참고해 백엔드 오류 코드를 그대로
베끼면 안 된다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

FRONTMATTER_RE = re.compile(r"^---\n(.*?)\n---\n", re.DOTALL)


def _frontmatter_field(text: str, key: str) -> str | None:
    match = FRONTMATTER_RE.match(text)
    if not match:
        return None
    for line in match.group(1).splitlines():
        if line.startswith(f"{key}:"):
            return line.split(":", 1)[1].strip() or None
    return None


def _bigrams(text: str) -> set[str]:
    squeezed = re.sub(r"\s+", "", text)
    return {squeezed[i:i + 2] for i in range(len(squeezed) - 1)}


@dataclass
class GatewayState:
    corpus: Path
    scope_key: str
    capability: str
    api_key: str
    # 테스트가 이 값을 올려 scope_changed 를 유발한다.
    scope_version: int = 47
    pages: dict[str, dict] = field(default_factory=dict)
    # 일정은 위키 트리에 없다 — 별도 JSON 에서 읽는다 (`--schedules`). 비워 두면 일정
    # 도구가 「결과가 없습니다」를 낸다.
    schedules: dict[str, dict] = field(default_factory=dict)

    @property
    def sources_dir(self) -> Path:
        """원본문서 디렉터리. 두 자리를 다 본다.

        `LocalVaultFS` 가 만든 트리는 `wiki/{scope}/sources/{documentId}/` 다
        (`backend_sim.py` 가 그 트리에 반영한다). 손으로 만든 코퍼스는 루트에 `sources/`
        를 두기도 한다. 앞의 것을 먼저 보고 없으면 뒤를 쓴다 — 못 찾으면 `documentRefs`
        가 조용히 빈 배열이 되고, 「이 위키가 어느 원본문서에서 나왔나」가 사라진다.
        """
        scoped = self.corpus / "wiki" / self.scope_key / "sources"
        return scoped if scoped.is_dir() else self.corpus / "sources"

    def load(self) -> None:
        base = self.corpus / "wiki" / self.scope_key
        self.pages = {}
        for index, path in enumerate(sorted((base / "pages").glob("*.md")), 1):
            text = path.read_text(encoding="utf-8")
            self.pages[str(index)] = {
                "wikiId": str(index),
                "title": _frontmatter_field(text, "title") or path.stem,
                "summary": _frontmatter_field(text, "description"),
                "wikiCategoryId": "9",
                "categoryName": "기본",
                "wikiPath": f"wiki/{self.scope_key}/pages/{path.name}",
                "contentHash": hashlib.sha256(text.encode()).hexdigest(),
                "updatedAt": "2026-07-27T09:00:00Z",
                "_body": text,
            }

    @property
    def index_markdown(self) -> str:
        path = self.corpus / "wiki" / self.scope_key / "index.md"
        return path.read_text(encoding="utf-8") if path.exists() else ""


def _not_found() -> JSONResponse:
    """허가 범위 밖과 없는 것을 한 값으로 덮는다. 403 을 쓰지 않는다."""
    return JSONResponse(
        status_code=404,
        content={"timestamp": "2026-07-30T00:00:00Z", "status": 404,
                 "error": "Not Found", "code": "WIKI_NOT_FOUND",
                 "message": "요청한 자료를 찾을 수 없습니다.", "path": "",
                 "fieldErrors": []})


def build_gateway(corpus: Path, *, scope_key: str, capability: str,
                  api_key: str, scope_version: int = 47,
                  schedules: list[dict] | None = None) -> FastAPI:
    state = GatewayState(Path(corpus), scope_key, capability, api_key,
                         scope_version)
    state.load()
    state.schedules = {row["scheduleId"]: dict(row) for row in (schedules or [])}

    app = FastAPI(title="AJT Wiki Query Gateway (fake)")
    app.state.gateway = state

    def guard(request: Request, scope_key_param: str | None) -> bool:
        # **요청마다 트리를 다시 읽는다.** 측정은 문서를 한 건씩 넣고, 앞 문서로 만든
        # 위키가 반영된 뒤에 다음 문서가 돈다 (`backend_sim.py`, FR-AI-003). 기동 시점에
        # 한 번만 읽으면 두 번째 문서가 늘 「위키 0장」을 보고, 그러면 겹침 검색도
        # 읽기 게이트도 성립하지 않아 측정이 기준선보다 쉬운 문제를 푼 값이 된다.
        # 진짜 백엔드는 DB 를 보므로 이런 갱신 지점이 따로 없다.
        state.load()
        if request.headers.get("X-Internal-API-Key") != state.api_key:
            return False
        if request.headers.get("X-Wiki-Capability") != state.capability:
            return False
        if scope_key_param is not None and scope_key_param != state.scope_key:
            return False
        return True

    def envelope(payload: dict) -> dict:
        return {"scopeVersion": state.scope_version, **payload}

    @app.get("/internal/v1/wiki-pages")
    async def wiki_pages(request: Request, scopeKey: str):
        if not guard(request, scopeKey):
            return _not_found()
        items = [{k: v for k, v in page.items() if k != "_body"}
                 for page in state.pages.values()]
        # 이 게이트웨이는 한 페이지로 다 준다 — 항상 None. 진짜 백엔드는 limit 을
        # 넘으면 커서를 발급한다.
        return envelope({"nextCursor": None, "items": items})

    @app.get("/internal/v1/wiki-search")
    async def wiki_search(request: Request, scopeKey: str, query: str,
                          limit: int = 10):
        if not guard(request, scopeKey):
            return _not_found()
        wanted = _bigrams(query)
        items = []
        for page in state.pages.values():
            if wanted & _bigrams(page["_body"]):
                items.append({
                    "wikiId": page["wikiId"], "title": page["title"],
                    "breadcrumb": page["title"],
                    "snippet": page["_body"][:120],
                    "chunkIndex": 0, "contentHash": page["contentHash"]})
        return envelope({"items": items[:limit]})

    @app.get("/internal/v1/wikis/{wiki_id}/content")
    async def wiki_content(request: Request, wiki_id: str, scopeKey: str):
        if not guard(request, scopeKey) or wiki_id not in state.pages:
            return _not_found()
        page = state.pages[wiki_id]
        return envelope({"wikiId": wiki_id, "title": page["title"],
                         "wikiPath": page["wikiPath"],
                         "contentMarkdown": page["_body"],
                         "contentHash": page["contentHash"]})

    @app.get("/internal/v1/wikis/{wiki_id}/relations")
    async def wiki_relations(request: Request, wiki_id: str, scopeKey: str):
        if not guard(request, scopeKey) or wiki_id not in state.pages:
            return _not_found()
        body = state.pages[wiki_id]["_body"]
        refs = [other["wikiId"] for other in state.pages.values()
                if other["wikiId"] != wiki_id
                and Path(other["wikiPath"]).name in body]
        backlinks = [other["wikiId"] for other in state.pages.values()
                     if Path(state.pages[wiki_id]["wikiPath"]).name
                     in other["_body"] and other["wikiId"] != wiki_id]
        return envelope({"wikiId": wiki_id, "wikiRefs": refs,
                         "documentRefs": [], "backlinks": backlinks})

    @app.get("/internal/v1/wiki-spaces/{scope_key}/index")
    async def wiki_index(request: Request, scope_key: str):
        if not guard(request, scope_key):
            return _not_found()
        return envelope({"scopeKey": scope_key,
                         "indexMarkdown": state.index_markdown})

    @app.get("/internal/v1/wiki-spaces/{scope_key}/categories")
    async def wiki_categories(request: Request, scope_key: str):
        if not guard(request, scope_key):
            return _not_found()
        return envelope({"items": [{"wikiCategoryId": "9", "name": "기본",
                                    "wikiCount": len(state.pages)}]})

    @app.get("/internal/v1/wiki-spaces/{scope_key}/relations")
    async def scope_relations(request: Request, scope_key: str):
        """범위 전체 간선. 역방향은 싣지 않는다 (계약 정책).

        `documentRefs` 는 본문에 원본문서 디렉터리 이름이 등장하는지로 도출한다.
        각주가 파일명으로 문서를 가리키므로(`tools/references.py`) 실제 백엔드의
        `wiki.document_refs` 와 같은 집합이 나온다.
        """
        if not guard(request, scope_key):
            return _not_found()
        sources = [path.name for path
                   in sorted(state.sources_dir.glob("*"))
                   if path.is_dir()]
        items = []
        for page in state.pages.values():
            body = page["_body"]
            items.append({
                "wikiId": page["wikiId"],
                "wikiRefs": [other["wikiId"] for other in state.pages.values()
                             if other["wikiId"] != page["wikiId"]
                             and Path(other["wikiPath"]).name in body],
                "documentRefs": [name for name in sources if name in body],
            })
        return envelope({"items": items})

    @app.get("/internal/v1/documents/{document_id}/parsed")
    async def document_parsed(request: Request, document_id: str,
                              scopeKey: str):
        if not guard(request, scopeKey):
            return _not_found()
        path = state.sources_dir / document_id / "parsed" / "content.md"
        if not path.exists():
            return _not_found()
        # 파싱본에는 scopeVersion 을 싣지 않는다 — 위키 스냅샷과 무관하다.
        return {"documentId": document_id, "originalFileName": f"{document_id}.pdf",
                "parsedMarkdown": path.read_text(encoding="utf-8")}

    # ---- 일정 조회 (계약 1.8.0 「일정 조회 API」 2개) ------------------------
    #
    # **허가값을 쓰지 않는다.** 일정 권한은 범위가 아니라 사용자·부서로 갈리므로 계약이
    # `questionId` 를 권한 판정 근거로 정했다 (헤더에 X-Wiki-Capability 가 없다).
    # 이 가짜는 `questionId` 가 있는지만 본다 — **진짜 권한 판정을 흉내 내지 않는다.**
    #
    # 시각은 UTC(`Z`)로 낸다. 계약이 그렇고, `from`·`to` 는 날짜라 **경계에서 KST 와
    # 어긋난다** — 그 판정은 백엔드 몫이고 이 가짜는 단순 문자열 비교로 자른다.

    @app.get("/internal/v1/schedules")
    async def schedules(request: Request, questionId: str):
        if request.headers.get("X-Internal-API-Key") != state.api_key:
            return _not_found()
        params = request.query_params
        start, end = params.get("from", ""), params.get("to", "")
        keyword = params.get("keyword") or ""
        limit = int(params.get("limit") or 50)
        rows = [row for row in state.schedules.values()
                if (not start or row["startAt"][:10] >= start)
                and (not end or row["startAt"][:10] <= end)
                and (not keyword or keyword in row["title"])]
        shown = [{k: v for k, v in row.items() if k != "content"}
                 for row in rows[:limit]]
        return {"items": shown, "truncated": len(rows) > limit}

    @app.get("/internal/v1/schedules/{schedule_id}")
    async def schedule_detail(request: Request, schedule_id: str, questionId: str):
        if request.headers.get("X-Internal-API-Key") != state.api_key:
            return _not_found()
        row = state.schedules.get(schedule_id)
        if not row:
            return _not_found()
        return dict(row)

    return app


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", required=True, help="위키 트리 루트")
    parser.add_argument("--scope", default="D1-D2")
    parser.add_argument("--capability", default="cap-local")
    parser.add_argument("--api-key", default="key-local")
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--schedules", help="일정 JSON (`schedules` 배열이 있는 파일)")
    args = parser.parse_args()

    rows = []
    if args.schedules:
        rows = json.loads(Path(args.schedules).read_text(encoding="utf-8"))["schedules"]

    import uvicorn
    uvicorn.run(build_gateway(Path(args.corpus), scope_key=args.scope,
                             capability=args.capability, api_key=args.api_key,
                             schedules=rows),
                host="127.0.0.1", port=args.port)


if __name__ == "__main__":
    main()
