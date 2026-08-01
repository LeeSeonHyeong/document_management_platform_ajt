"""Backend simulator — everything Spring will own, faked so the agent can run.

**This file is disposable.** Registering an upload, running one job per document,
validating the result and committing it is the backend's work (API convention
2.1/10.2). Nothing under `mcp/` or `runtime/` imports this. When Spring exists,
this goes away and `vaultfs/local.py` is replaced by a Spring adapter; the tools
and the runtimes do not change.

What it fakes, and where each rule comes from:

  * one job per document, in upload order, never two at once (FR-AI-003)
  * a failing document is recorded and the batch continues (FR-AI-008)
  * `work/{jobId}` holds `state.json`, `input/`, `output/` (AJT 8절, DR-005)
  * the work layer is verified with `lint` before anything is promoted, and a
    failure leaves the live tree untouched (DR-007/008/009, NFR-REL-001)
  * `wiki_id` is assigned at commit, which is why pages are named with a key the
    agent allocated instead (DR-015 as amended, DR-016)
  * the change set handed back is the per-document 작업 요약 (FR-AI-009)

두 경로가 있다. 기본은 런타임을 직접 부르는 옛 경로(`_ingest_one`)이고, `--via-api URL` 은
AI 서버를 **HTTP 로** 부른다 (`_ingest_via_api` — 선택·변환 2단계, 설계 §2). 후자가
「측정 경로 = 프로덕션 경로」다: 계약 스키마·세션·하이드레이션·lint 게이트·응답 조립을
전부 실제로 통과하고, 이 파일은 Spring 이 할 일(주소 치환·카테고리 ID 발급·목차 재구성·
`wikiId` 발급·반영)만 한다. 옛 경로는 비용·시간 회귀 측정 때문에 남긴다 — 계약 응답에는
토큰·비용·툴 호출 수가 없다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
# 이관 주석: 옛 트리에서 ROOT 는 ai-server/ 였다. 지금은 experiments/ 이고
# 패키지는 ../src/ 에 있다 (wiki_mcp·agent_runtime·wiki_api).
sys.path.insert(0, str(ROOT.parent / "src"))

from wiki_mcp.tools.lint import LintHandler  # noqa: E402
from wiki_mcp.vaultfs import INDEX_ADDRESS, LocalVaultFS  # noqa: E402
from wiki_mcp.vaultfs.local import (  # noqa: E402
    bootstrap_scope,
    commit_job,
    discard_job,
    register_source,
    write_job_state,
)

from experiment import Experiment, resolve_root  # noqa: E402
from agent_runtime import ingest_instruction, load_runtime  # noqa: E402


class WikiIdSequence:
    """Stands in for `wiki.wiki_id` AUTO_INCREMENT, persisted next to the index."""

    def __init__(self, root: Path):
        self.path = root / ".llmwiki" / "next_wiki_id"
        self.value = int(self.path.read_text()) if self.path.exists() else 3001

    def __call__(self) -> int:
        current = self.value
        self.value += 1
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(str(self.value))
        return current


WRITE_TOOLS = ("create", "edit", "append", "merge", "delete")


async def _verify(scope_key: str, job_id: str, scope_id: str) -> str:
    """Run the same lint the agent was told to clear (DR-007: 검증)."""
    fs = LocalVaultFS(scope_key, job_id)
    scope = await fs.resolve_scope(scope_key)
    return await LintHandler(fs, scope).run()


def _bypassed_server(changes: list[dict], tool_calls: dict[str, int]) -> bool:
    """Did something write without going through an MCP tool?

    The runtimes are supposed to reach the store only through the server — that is
    where permission, the reference graph and the chunk index live. DeepAgents
    ships its own `write_file`, and if the harness profile fails to exclude it the
    agent produces a wiki that skipped all three, silently. Neither runtime can
    prove the negative from its own side, but this can after the fact: a change set
    with no write tool logged means the file appeared some other way.

    Only meaningful when tool counting is on; an empty log is inconclusive, not a
    pass.
    """
    if not changes or not tool_calls:
        return False
    return not any(tool_calls.get(name) for name in WRITE_TOOLS)


def _outcome(record: dict, result, lint_failed: bool, dry_run: bool = False) -> str:
    """`committed` / `unchanged` / `failed` / `dry-run`.

    `unchanged` has to be its own thing. Re-processing a document already in the
    wiki should change nothing (FR-DOC-012), and the idempotence run proved the
    agent does exactly that — it searched, read the two pages holding the content,
    and wrote nothing. Counting that as a failure reported a correct run as broken.

    The distinction is whether the agent reached a conclusion or never got going:
    a clean lint plus tool calls means it looked and decided; no tool calls at all
    means it never worked.

    `dry_run` only ever downgrades a would-be `committed`/`unchanged` result to
    `dry-run` — it must never mask a genuine failure. A bypassed-server error or a
    failing lint during a dry run is still a failure; the batch summary's failure
    loop only scans for `outcome == "failed"`, so mislabeling it would make a real
    problem vanish from the report silently.
    """
    if record.get("error"):
        return "failed"
    if lint_failed:
        return "failed"
    if dry_run:
        return "dry-run"
    if record["changes"]:
        return "committed"
    if not result.tool_calls:
        return "failed"
    return "unchanged"


def dry_run_warning(dry_run: bool, from_experiment: str | None) -> str | None:
    """`--dry-run` 이 라이브 저장소를 정말로 건드리지 않는다고 말할 수 없다 (I6).

    반영(`commit_job`)만 건너뛴다. 그 앞의 `bootstrap_scope`·`register_source`·작업 상태
    기록은 그대로 돌고, 에이전트도 같은 루트의 작업 층에 쓴다. 그래서 `--from-experiment`
    없이 기존 실험 루트에 `--dry-run` 을 걸면 그 실험 데이터가 바뀔 수 있다.

    비변형 보장은 이번 범위 밖이다 — 복사본에서 돌리는 것이 안전한 사용법이고, 그것을
    호출자가 알 수 있게 경고한다. README 「정직한 한계」 절에 같은 내용이 있다.
    """
    if not dry_run or from_experiment:
        return None
    return ("경고: --dry-run 은 반영만 건너뛴다. 원본문서 등록·작업 상태·작업 층 쓰기는 "
            "그대로 일어나므로 기존 실험 데이터가 바뀔 수 있다 — "
            "--from-experiment <slug> 로 복사본에서 돌리는 것을 권한다.")


async def _ingest_one(root: Path, scope_key: str, seq: int, source: Path,
                      runtime, sequence: WikiIdSequence,
                      dry_run: bool = False,
                      transport: str = "mcp") -> dict:
    job_id = f"{9000 + seq}"
    document_id = str(100 + seq)

    scope_id = await LocalVaultFS.open(root, scope_key, job_id)
    await bootstrap_scope(scope_key)
    registered = await register_source(
        scope_key, document_id, source.name, source.read_text(encoding="utf-8")
    )
    write_job_state(scope_key, job_id, {
        "type": "wiki-convert", "step": "agent", "scopeKey": scope_key,
        "documentIds": [document_id], "documentId": document_id,
    })
    instruction = ingest_instruction(registered["address"], scope_key)
    print(f"[{seq}] {source.name} ...", flush=True)
    started = time.monotonic()

    if transport == "in-process":
        # **배송 경로다** (S15P11B106-152). 도구가 이 프로세스 안에 있으므로 저장소를
        # 닫지 않고 그대로 넘긴다 — 쓰는 쪽이 하나뿐이라 SQLite 잠금 걱정이 없다.
        result = await runtime.arun(instruction, fs=LocalVaultFS(scope_key, job_id),
                                    scope_id=scope_id, root=root,
                                    scope_key=scope_key, job_id=job_id)
        await LocalVaultFS.close()
    else:
        # MCP 서버가 자기 연결을 연다 — SQLite 파일 하나에 쓰는 쪽이 둘이면 잠금이 난다.
        await LocalVaultFS.close()
        # **스레드로 띄운다.** `DeepAgentsRuntime.run` 은 안에서 `asyncio.run` 을 부르는데
        # 이 함수는 이미 도는 루프 안이라 그대로 부르면 `asyncio.run() cannot be called
        # from a running event loop` 로 죽는다. `wiki_api/session.py::run_agent` 가 sync
        # 런타임에 하는 것과 같다. claude-code 는 subprocess 라 원래 문제가 없었고,
        # 그래서 이 경로가 deepagents 로 한 번도 안 돌아 봤다.
        result = await asyncio.to_thread(
            runtime.run, instruction, root=root, scope_key=scope_key, job_id=job_id)

    record: dict = {
        "seq": seq,
        "jobId": job_id,
        "documentId": document_id,
        "document": source.name,
        "elapsedSeconds": result.elapsed_seconds or round(time.monotonic() - started, 1),
        "toolCalls": result.tool_calls,
        "inputTokens": result.input_tokens,
        "outputTokens": result.output_tokens,
        "turns": result.turns,
        "costUsd": round(result.cost_usd, 4),
        "error": result.error,
        "reply": result.text,
    }
    # 턴별·툴별 내역. 총계만으로는 D8 을 좁힐 수 없다 — 시간 ≈ 출력토큰 ÷ 55 로 거의
    # 일정하므로 출력 토큰이 곧 지연시간이고, 줄이려면 어느 턴에서 나오는지부터 알아야
    # 한다. `stream-json` 을 내지 못하는 런타임에서는 `None` 이라 키를 빼 둔다.
    if result.detail is not None:
        record["detail"] = result.detail
        # 서버측 집계와 스트림측 집계가 다르면 CLI 가 부른 툴이 서버에 도달하지 않았다는
        # 뜻이다. 어느 한쪽만으로는 그것을 알 수 없다.
        stream_calls = result.detail.get("toolCalls") or {}
        if stream_calls != result.tool_calls:
            record["toolCallMismatch"] = {
                "server": result.tool_calls, "stream": stream_calls,
            }

    await LocalVaultFS.open(root, scope_key, job_id)
    try:
        fs = LocalVaultFS(scope_key, job_id)
        record["changes"] = await fs.pending_changes(scope_id)
        record["lint"] = await _verify(scope_key, job_id, scope_id)
        lint_failed = "error 0" not in record["lint"] and "lint 통과" not in record["lint"]

        if _bypassed_server(record["changes"], result.tool_calls):
            record["error"] = (
                "MCP 서버를 우회해 파일이 만들어졌다 — 변경은 있는데 쓰기 툴 호출이 없다. "
                "런타임의 내장 파일 툴이 차단되지 않았을 가능성이 크다."
            )

        # 응답은 기록하지만 반영하지 않는다 — 단, error/lint 실패는 dry-run 이라도 failed다
        # (_outcome 이 error·lint_failed 를 dry_run 보다 먼저 본다).
        record["outcome"] = _outcome(record, result, lint_failed, dry_run=dry_run)

        if record["outcome"] == "committed":
            record["committed"] = await commit_job(scope_key, job_id, scope_id, sequence)
        else:
            # DR-009: nothing verified, nothing served.
            record["committed"] = []
            record["discarded"] = await discard_job(scope_key, job_id, scope_id)
            if record["outcome"] == "failed" and not record["error"]:
                record["error"] = "검증 실패 — 반영하지 않았다"
    finally:
        await LocalVaultFS.close()

    status = record["error"] or (
        f"{'완료' if record['outcome'] == 'committed' else '변경 없음'} "
        f"(툴 {sum(result.tool_calls.values())}회, "
        f"반영 {len(record['committed'])}건, ${result.cost_usd:.2f})"
    )
    print(f"    {record['elapsedSeconds']:.1f}s  {status}", flush=True)
    return record


# ----- via-api: 측정 경로 = 프로덕션 경로 -----------------------------------
#
# 여기 아래가 Spring 이 v1.1.0 계약으로 하게 될 일이다 (설계 §2). 위의 `_ingest_one` 은
# 런타임을 직접 부르지만(옛 경로, 지금까지의 측정), 이쪽은 **HTTP 로 AI 서버를 두 번
# 부르고 응답만 반영한다** — 즉 이 경로로 돈 측정은 `api/` 계약 층을 실제로 통과한다.
#
# 주소 체계가 두 개라는 것이 이 배선의 핵심 난점이다 (설계 §3):
#
#   store(여기)   `pages/{pageKey}.md`  — 에이전트가 발급한 키. `wiki_path` 컬럼(DR-016)
#   계약(AI 서버) `pages/{wikiId}.md`   — selectedWikis 에 wikiPath 가 없다
#
# 그래서 요청을 만들 때 목차·본문의 페이지 링크를 wikiId 주소로 바꾸고, 응답을 반영할 때
# 되돌린다. 그 치환이 곧 Spring 이 할 일이다 — 「본문에 남은 페이지 링크를 실제 wikiId 로
# 치환」(`api/schemas.py` 의 `wikiPath` 주석).

_PAGE_LINK_RE = re.compile(r"pages/[A-Za-z0-9_-]+\.md")

# Spring 의 read timeout 과 같은 자리 — 에이전트 상한 30분 + 여유 (설계 §1).
API_TIMEOUT_SECONDS = 1900

SELECT_PATH = "/internal/v1/wiki-context-selections"
TRANSFORM_PATH = "/internal/v1/wiki-transformations"


def api_client(base_url: str, api_key: str):
    """`--via-api URL` 용 HTTP 클라이언트.

    테스트는 이것을 쓰지 않고 `httpx.AsyncClient(transport=ASGITransport(app=...))` 를
    직접 주입한다 — 서버를 띄우지 않고 같은 코드 경로를 통과시키기 위해서다. 그래서
    `_ingest_via_api` 는 URL 이 아니라 **클라이언트를 받는다**.
    """
    import httpx

    return httpx.AsyncClient(base_url=base_url.rstrip("/"),
                             headers={"X-Internal-API-Key": api_key},
                             timeout=API_TIMEOUT_SECONDS)


class CategoryRegistry:
    """`wiki_category.wiki_category_id` AUTO_INCREMENT 대역 (DR-019).

    카테고리는 에이전트가 관리하고 관리자는 조회만 한다 — 그러나 **ID 를 발급하는 것은
    Spring 이다**. 요청의 `currentCategories` 가 이미 있는 이름을 실어 보내야 에이전트가
    같은 분류를 다시 만들지 않는다 (C4 수렴, `api/changes.py.CategoryRefs`).
    """

    def __init__(self, root: Path):
        self.path = root / ".llmwiki" / "categories.json"
        self.names: dict[str, str] = (
            json.loads(self.path.read_text(encoding="utf-8")) if self.path.exists() else {})

    def id_for(self, name: str | None) -> str | None:
        if not name:
            return None
        if name not in self.names:
            self.names[name] = str(len(self.names) + 1)
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.names, ensure_ascii=False),
                                 encoding="utf-8")
        return self.names[name]

    def current(self, names) -> list[dict]:
        return [{"wikiCategoryId": self.id_for(name), "name": name}
                for name in sorted({n for n in names if n})]


def _page_key(address: str) -> str | None:
    if address.startswith("pages/") and address.endswith(".md"):
        return address[len("pages/"):-3]
    return None


def _relink(text: str, mapping: dict[str, str]) -> str:
    """본문·목차의 페이지 링크 주소를 갈아끼운다. 매핑에 없는 링크는 그대로 둔다."""
    return _PAGE_LINK_RE.sub(lambda m: mapping.get(m.group(0), m.group(0)), text or "")


def _summary_of(content: str) -> str | None:
    from wiki_mcp.tools.write import extract_frontmatter_field, parse_frontmatter

    return extract_frontmatter_field(parse_frontmatter(content or ""), "description")


def _address_from_wiki_path(wiki_path: str | None, scope_key: str) -> str | None:
    """`wiki/{scopeKey}/pages/a3f2c1d4.md` → `pages/a3f2c1d4.md`.

    신규 페이지의 `pageKey` 는 에이전트가 발급한 값이라 `wikiId` 에서 유도할 수 없다 —
    응답의 `wikiPath` 가 유일한 단서다 (`api/schemas.py` 의 `wikiPath` 주석).
    """
    if not wiki_path:
        return None
    prefix = f"wiki/{scope_key}/"
    address = wiki_path[len(prefix):] if wiki_path.startswith(prefix) else wiki_path.lstrip("/")
    return address or None


async def _collect_context(scope_key: str, scope_id: str,
                           categories: CategoryRegistry) -> dict:
    """자기 store 에서 요청에 실을 재료를 만든다 — Spring 의 DB 조회 자리.

    페이지 링크를 `pages/{wikiId}.md` 로 바꾼 목차를 함께 낸다. 1단계 선택이 목차 링크에
    실재하는 ID 만 남기므로(`api/selection.py.index_wiki_ids`), 여기서 바꾸지 않으면 선택은
    pageKey 를 고르고 2단계는 그 ID 로 본문을 찾지 못해 **항상 빈 문맥**으로 돈다.
    """
    fs = LocalVaultFS(scope_key)
    pages: dict[str, dict] = {}
    to_wiki_ids: dict[str, str] = {}
    for row in await fs.list_documents(scope_id, with_content=True):
        if row.get("kind") != "page" or not row.get("wiki_id"):
            continue
        wiki_id = str(row["wiki_id"])
        content = row.get("content") or ""
        pages[wiki_id] = {
            "wikiId": wiki_id, "address": row["address"],
            "title": row.get("title") or wiki_id, "category": row.get("category"),
            "summary": _summary_of(content), "content": content,
        }
        to_wiki_ids[row["address"]] = f"pages/{wiki_id}.md"

    index_row = await fs.get(scope_id, INDEX_ADDRESS)
    return {
        "pages": pages,
        # 계약 주소 → 로컬 주소, 그리고 그 역. 반영할 때 되돌리는 데 쓴다.
        "toWikiIds": to_wiki_ids,
        "toAddresses": {v: k for k, v in to_wiki_ids.items()},
        "index": _relink((index_row or {}).get("content") or "", to_wiki_ids),
        "categories": categories.current(p["category"] for p in pages.values()),
    }


async def _selected_wikis(fs, scope_id: str, context: dict, wiki_ids: list[str],
                          categories: CategoryRegistry) -> list[dict]:
    """선택된 ID → `SelectedWiki` 본문. 관계까지 실어 보낸다 (DR-002·003).

    Spring 은 선택 응답의 ID 를 **재검증한 뒤** 본문을 읽는다 (계약 정책). 여기서도 store
    에 실재하는 ID 만 남긴다 — 지어낸 ID 로 없는 페이지를 실어 보내지 않는다.
    """
    selected = []
    for wiki_id in wiki_ids:
        page = context["pages"].get(str(wiki_id))
        if not page:
            continue
        documents: list[str] = []
        wikis: list[str] = []
        for edge in await fs.get_forward_references(scope_id, page["address"]):
            if edge["reference_type"] == "cites" and edge.get("source_id"):
                documents.append(str(edge["source_id"]))
            elif edge["reference_type"] == "links_to":
                # `address` 다 — `get_forward_references` 는 대상 행을 조인한다. 같은 자리에서
                # `target_address` 를 읽던 `api/changes.py` 의 버그를 그대로 베껴 왔었다.
                target = context["toWikiIds"].get(edge["address"])
                if target:
                    wikis.append(target[len("pages/"):-3])
        selected.append({
            "wikiId": page["wikiId"],
            "categoryId": categories.id_for(page["category"]),
            "title": page["title"],
            "summary": page["summary"],
            "contentMarkdown": _relink(page["content"], context["toWikiIds"]),
            "documentRefs": sorted(set(documents)),
            "wikiRefs": sorted(set(wikis)),
        })
    return selected


async def _post(client, path: str, body: dict) -> tuple[dict | None, str | None]:
    """(응답, 오류). 계약 오류 구조(API_컨벤션 6.2)를 한 줄로 접어 기록한다."""
    response = await client.post(path, json=body)
    if response.status_code == 200:
        return response.json(), None
    try:
        payload = response.json()
    except Exception:
        payload = None
    if not isinstance(payload, dict):
        return None, f"{path} HTTP {response.status_code} — {response.text[:200]}"
    # 오류 본문은 평평하다 — `code`·`message`·`failureStage` 가 최상위다 (API_컨벤션 6.2,
    # `api/errors.py.ErrorBody`). `error` 는 HTTP 사유 문구지 객체가 아니다.
    stage = payload.get("failureStage")
    return None, (f"{path} HTTP {response.status_code} — "
                  f"{payload.get('code') or payload.get('error') or '?'}"
                  f"{f'/{stage}' if stage else ''}: "
                  f"{payload.get('message') or response.text[:200]}")


_INDEX_LINE_RE = re.compile(r"^\s*[-*]\s*\[[^\]]*\]\((?P<target>[^)]+?\.md)\)")


def _index_markdown(current: str, entries: list[dict], targets: dict[str, str],
                    merges: dict[str, str] | None = None) -> str:
    """`indexEntries` → 목차 본문. 목차를 재구성하는 것은 Spring 이다 (설계 §3).

    **덮어쓰지 않고 겹친다.** 응답의 `indexEntries` 는 AI 서버가 **본** 위키만 담는다 —
    이번 요청에 실리지 않은 위키(selectedWikis 밖)는 거기에 없다. 통째로 갈아치우면 그
    위키들의 목차 줄이 사라지고, 목차가 1단계 선택의 유일한 입력이므로(설계 §5) 그 위키는
    **다시는 선택되지 않는다** — 한 번 밖에 나면 영구히 보이지 않는다. 그래서 응답에 있는
    줄만 갱신하고 나머지는 자리를 지킨다. 협의 목록에 올릴 값: Spring 의 목차 재구성도
    같아야 한다.

    frontmatter 는 지금 것을 그대로 이어 쓴다 — 목차의 frontmatter 는 이 저장소가
    `bootstrap_scope` 에서 만든 것이고, 버리면 `lint` 가 `missing-frontmatter` 로 막는다.
    """
    head = ""
    if current.startswith("---"):
        end = current.find("\n---", 3)
        if end >= 0:
            head = current[:end + 4].rstrip() + "\n\n"

    incoming: dict[str, str] = {}
    for entry in sorted(entries, key=lambda e: e.get("order") or 0):
        address = targets.get(str(entry.get("wikiRef")))
        if not address:
            continue    # 매달린 참조는 버린다 (`changes.parse_index_entries` 와 같은 규칙)
        summary = (entry.get("summary") or "").strip()
        incoming[address] = (f"- [{entry.get('title') or address}]({address})"
                             + (f" — {summary}" if summary else ""))

    lines: list[str] = []
    seen: set[str] = set()
    merged = merges or {}
    for line in current.splitlines():
        match = _INDEX_LINE_RE.match(line)
        if not match:
            continue
        address = f"pages/{match['target'].split('/')[-1]}"
        if address in merged:
            # 흡수된 페이지의 줄은 남은 페이지 줄로 바뀐다. 남은 쪽이 응답에 없으면
            # (있을 수 없지만) 매달린 줄을 남기느니 지운다.
            address = merged[address]
            if address not in incoming:
                continue
        if address in seen:
            continue
        seen.add(address)
        lines.append(incoming.get(address, line.strip()))
    for address, line in incoming.items():
        if address not in seen:
            seen.add(address)
            lines.append(line)
    return f"{head}# 위키 목차\n\n" + "\n".join(lines) + "\n"


async def _apply_transform(scope_key: str, scope_id: str, job_id: str, response: dict,
                           context: dict, categories: CategoryRegistry) -> dict:
    """응답을 작업 층에 옮긴다. 반영(commit)은 `_ingest_via_api` 가 lint 뒤에 한다.

    Spring 이 하는 세 가지를 그대로 한다: ① `tempWikiId`/`wikiId` 를 파일 주소로 되돌리고
    ② 본문의 계약 주소 링크를 로컬 주소로 치환하고 ③ `indexEntries` 로 목차를 재구성한다.
    실제 `wikiId` 발급은 `commit_job`(= AUTO_INCREMENT) 이 한다.
    """
    from wiki_mcp.tools.references import sync_references

    fs = LocalVaultFS(scope_key, job_id)

    # ① 참조 → 로컬 주소.
    targets: dict[str, str] = {}
    for change in response.get("wikiChanges") or []:
        ref = change.get("wikiId") or change.get("tempWikiId")
        if not ref:
            continue
        address = (context["pages"].get(str(change.get("wikiId")), {}).get("address")
                   or _address_from_wiki_path(change.get("wikiPath"), scope_key)
                   or await fs.allocate_page(scope_id))
        targets[str(ref)] = address
    # 이번에 안 바뀐 페이지도 목차가 가리킨다.
    for wiki_id, page in context["pages"].items():
        targets.setdefault(wiki_id, page["address"])

    links = dict(context["toAddresses"])
    links.update({f"pages/{ref}.md": address for ref, address in targets.items()})

    # 카테고리 이름 — 새로 만든 것은 여기서 ID 를 받는다 (Spring 의 INSERT 자리).
    names = {str(c.get("wikiCategoryId") or c.get("categoryId")): c.get("name")
             for c in context["categories"]}
    for change in response.get("categoryChanges") or []:
        if change.get("action") == "create" and change.get("name"):
            categories.id_for(change["name"])
            names[str(change.get("tempCategoryId"))] = change["name"]

    applied: list[str] = []
    merges: dict[str, str] = {}
    for change in response.get("wikiChanges") or []:
        ref = str(change.get("wikiId") or change.get("tempWikiId") or "")
        address = targets.get(ref)
        if not address:
            continue
        if change.get("action") in ("remove", "merge"):
            # 병합은 삭제가 아니다 — 흡수된 페이지를 가리키던 것들이 **남을 곳**이 있다.
            into = targets.get(str(change.get("mergedIntoRef") or ""))
            if change.get("action") == "merge" and into and into != address:
                merges[address] = into
            await fs.remove(scope_id, address)
            applied.append(address)
            continue
        content = _relink(change.get("contentMarkdown") or "", links)
        await fs.write(scope_id, address, content, title=change.get("title"),
                       category=names.get(str(change.get("wikiCategoryRef"))))
        # 툴(`tools/write.py`)이 쓰기마다 부르는 것과 같다 — 관계 그래프와 각주 근거가
        # 여기서 채워지고, 그것이 `pending_changes` 의 evidence 와 `lint` 의 입력이다.
        await sync_references(fs, scope_id, address, content)
        applied.append(address)

    applied += await _redirect_merged_links(fs, scope_id, merges)

    entries = response.get("indexEntries") or []
    if entries or merges:
        index_row = await fs.get(scope_id, INDEX_ADDRESS)
        current = (index_row or {}).get("content") or ""
        content = _index_markdown(current, entries, targets, merges)
        # **같으면 쓰지 않는다.** `indexEntries` 는 변경 여부와 무관하게 목차 전체가 오므로
        # (`api/changes.py.parse_index_entries` 는 하이드레이션된 목차도 그대로 읽는다),
        # 무조건 쓰면 변경 0 인 재처리가 「목차 1건 변경」으로 나와 멱등이 깨진다
        # (FR-DOC-012).
        if content != current:
            await fs.write(scope_id, INDEX_ADDRESS, content)
            await sync_references(fs, scope_id, INDEX_ADDRESS, content)
            applied.append(INDEX_ADDRESS)
    return {"addresses": applied, "targets": targets}


async def _redirect_merged_links(fs, scope_id: str, merges: dict[str, str]) -> list[str]:
    """흡수된 페이지를 가리키던 본문 링크를 남은 페이지로 옮긴다 (DR-002·003).

    **에이전트가 대신 해 줄 수 없다.** 이번 요청에 실린 위키는 `selectedWikis` 뿐이고,
    선택 밖에 있던 페이지는 AI 서버가 존재조차 모른다 — 그쪽 본문의 링크를 고치는 것은
    구조적으로 백엔드 몫이다. 안 고치면 반영 뒤 `lint` 가 `dangling-link` 로 그 범위를
    막는다(실제로 막았다).

    범위 전체를 훑는다. 위키 1개 범위는 작고(측정에서 페이지 수십 장), 링크를 역인덱스로
    찾으려면 `document_references` 를 병합 시점 기준으로 다시 읽어야 하는데 방금 지운
    페이지의 간선은 이미 갈아엎힌 뒤다.
    """
    if not merges:
        return []
    from wiki_mcp.tools.references import sync_references

    touched: list[str] = []
    for row in await fs.list_documents(scope_id, with_content=True):
        if row.get("kind") != "page" or row["address"] in merges:
            continue
        content = row.get("content") or ""
        updated = _relink(content, merges)
        if updated == content:
            continue
        await fs.write(scope_id, row["address"], updated)
        await sync_references(fs, scope_id, row["address"], updated)
        touched.append(row["address"])
    return touched


def _api_outcome(record: dict, lint_failed: bool, dry_run: bool) -> str:
    """`_outcome` 의 via-api 판본.

    툴 호출 수를 볼 수 없다 — 계약 응답에 그런 필드가 없다(있어서도 안 된다). 그래서
    「돌지 않았다」와 「돌고 변경 0」을 툴 호출로 가릴 수 없고, 대신 **HTTP 로 가린다**:
    실패는 200 이 아니거나 오류 구조로 온다. 200 + 변경 0 은 정당한 멱등 재처리다
    (FR-DOC-012). `_bypassed_server` 도 여기서는 판정 불가라 부르지 않는다 — 쓰기는
    이 파일이 하고 에이전트는 AI 서버 안에서 돈다.
    """
    if record.get("error"):
        return "failed"
    if lint_failed:
        return "failed"
    if dry_run:
        return "dry-run"
    return "committed" if record["changes"] else "unchanged"


async def _ingest_via_api(root: Path, scope_key: str, seq: int, source: Path,
                          client, sequence: WikiIdSequence,
                          categories: CategoryRegistry, dry_run: bool = False) -> dict:
    """문서 1건 — 선택 POST → 변환 POST → 반영. HTTP 왕복 2회 (설계 §2)."""
    job_id = f"{9000 + seq}"
    document_id = str(100 + seq)
    text = source.read_text(encoding="utf-8")

    scope_id = await LocalVaultFS.open(root, scope_key, job_id)
    await bootstrap_scope(scope_key)
    # **파일명이 `document-{documentId}` 다.** 계약에 원본 파일명 필드가 없어서
    # (`TransformRequest` 는 `parsedMarkdown` 만 받는다) AI 서버는 `stage_source` 의 기본
    # 이름으로 문서를 색인하고, 에이전트는 그 이름으로 각주를 단다. 여기서 실제 파일명으로
    # 등록하면 반영 뒤 `lint` 가 그 각주를 `unresolved-citation` 으로 막는다 — 이름을 맞춰
    # 둔다. 협의 목록: 계약에 `originalFileName` 을 넣으면 사라질 우회다.
    registered = await register_source(scope_key, document_id, f"document-{document_id}",
                                       text)
    write_job_state(scope_key, job_id, {
        "type": "wiki-convert", "step": "select", "scopeKey": scope_key,
        "documentIds": [document_id], "documentId": document_id,
    })
    context = await _collect_context(scope_key, scope_id, categories)
    fs = LocalVaultFS(scope_key, job_id)
    # 선택 응답이 오기 전에는 어느 위키를 실을지 모른다 — 관계 조회까지 마치고 닫는다.
    await LocalVaultFS.close()

    print(f"[{seq}] {source.name} (via-api) ...", flush=True)
    started = time.monotonic()
    record: dict = {
        "seq": seq, "jobId": job_id, "documentId": document_id, "document": source.name,
        "path": registered["relativePath"], "viaApi": True,
        "toolCalls": {}, "inputTokens": 0, "outputTokens": 0, "turns": 0, "costUsd": 0.0,
        "error": None, "reply": "", "changes": [], "committed": [],
    }

    selection, error = await _post(client, SELECT_PATH, {
        "jobId": job_id, "documentId": document_id, "scopeKey": scope_key,
        "parsedMarkdown": text, "currentIndex": context["index"],
        "changeType": "document_added",
    })
    response = None
    if error:
        record["error"] = error
    else:
        record["selection"] = selection
        await LocalVaultFS.open(root, scope_key, job_id)
        try:
            selected = await _selected_wikis(fs, scope_id, context,
                                             selection.get("wikiIds") or [], categories)
        finally:
            await LocalVaultFS.close()
        record["selectedWikis"] = [w["wikiId"] for w in selected]

        response, error = await _post(client, TRANSFORM_PATH, {
            "jobId": job_id, "documentId": document_id, "scopeKey": scope_key,
            "parsedMarkdown": text, "currentIndex": context["index"],
            "currentCategories": context["categories"], "selectedWikis": selected,
            "changeType": "document_added",
        })
        record["error"] = error
        if response is not None:
            record["response"] = response
            record["reply"] = response.get("summary") or ""
            record["relationChanges"] = response.get("relationChanges") or []
            record["categoryChanges"] = response.get("categoryChanges") or []

    record["elapsedSeconds"] = round(time.monotonic() - started, 1)

    await LocalVaultFS.open(root, scope_key, job_id)
    try:
        lint_failed = False
        if response is not None:
            await _apply_transform(scope_key, scope_id, job_id, response, context,
                                   categories)
            record["changes"] = await fs.pending_changes(scope_id)
            record["lint"] = await _verify(scope_key, job_id, scope_id)
            lint_failed = ("error 0" not in record["lint"]
                           and "lint 통과" not in record["lint"])

        record["outcome"] = _api_outcome(record, lint_failed, dry_run)
        if record["outcome"] == "committed":
            record["committed"] = await commit_job(scope_key, job_id, scope_id, sequence)
        else:
            # DR-009: 검증하지 않은 것은 내보내지 않는다. dry-run 도 같은 자리에서 버린다.
            record["discarded"] = await discard_job(scope_key, job_id, scope_id)
            if record["outcome"] == "failed" and not record["error"]:
                record["error"] = "검증 실패 — 반영하지 않았다"
    finally:
        await LocalVaultFS.close()

    status = record["error"] or (
        f"{'완료' if record['outcome'] == 'committed' else record['outcome']} "
        f"(선택 {len(record.get('selectedWikis') or [])}건, "
        f"반영 {len(record['committed'])}건)")
    print(f"    {record['elapsedSeconds']:.1f}s  {status}", flush=True)
    return record


async def _run_batch(root: Path, scope_key: str, sources: list[Path],
                     runtime_name: str, model: str | None,
                     dry_run: bool = False, client=None,
                     effort: str | None = None,
                     transport: str = "mcp") -> dict:
    """`client` 가 있으면 AI 서버를 HTTP 로 부른다 (`--via-api`), 없으면 옛 경로다.

    `effort` 를 여기까지 넘겨야 한다. `manifest.json` 에만 적고 실행에 안 걸면 그 기록이
    거짓이 되고 대조 측정 전체가 무의미해진다.
    """
    if client is not None:
        sequence = WikiIdSequence(root)
        categories = CategoryRegistry(root)
        records = []
        for seq, source in enumerate(sources, start=1):
            records.append(await _ingest_via_api(root, scope_key, seq, source, client,
                                                 sequence, categories, dry_run=dry_run))
        return {
            "scope": scope_key, "runtime": "via-api", "model": None,
            "order": [s.name for s in sources], "documents": records,
        }

    runtime = load_runtime(runtime_name, model, effort=effort)
    if transport == "in-process" and not hasattr(runtime, "arun"):
        raise SystemExit(
            f"{runtime_name} 런타임에는 in-process 경로가 없다 — 도구가 하위 프로세스에 "
            "있다. `--transport mcp` 로 재거나 `--runtime deepagents` 를 쓴다")
    sequence = WikiIdSequence(root)
    records = []
    for seq, source in enumerate(sources, start=1):
        # FR-AI-003: one document at a time, in order, next one only after the
        # previous is reflected. FR-AI-008: a failure does not stop the rest.
        records.append(await _ingest_one(root, scope_key, seq, source, runtime, sequence,
                                         dry_run=dry_run, transport=transport))
    return {
        "scope": scope_key,
        "runtime": runtime_name,
        "transport": transport,
        "model": getattr(runtime, "model", None),
        "effort": getattr(runtime, "effort", None),
        "order": [s.name for s in sources],
        "documents": records,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="원본문서를 위키로 반영한다 (백엔드 시뮬레이터)")
    parser.add_argument("sources", nargs="*", help="원본문서 파일 경로")
    parser.add_argument(
        "--experiment", default=None, metavar="SLUG",
        help="experiments/<SLUG>/ 아래에 저장소·리포트·manifest를 함께 둔다 (권장). "
             "예: 2026-07-28-sonnet46-12docs",
    )
    parser.add_argument("--purpose", default="", help="이 측정으로 무엇을 보려는지 한 줄")
    parser.add_argument("--root", default=None, help="임시 확인용 저장소 루트")
    parser.add_argument("--report", default=None, help="임시 확인용 리포트 경로")
    parser.add_argument("--scope", default="ALL", help="scope_key")
    parser.add_argument("--runtime", default="claude-code", choices=["claude-code", "deepagents"])
    # 측정 경로와 배송 경로가 갈려 있으면 기록한 수치가 배송되는 것과 다른 것을 잰 값이
    # 된다. S15P11B106-152 로 배송이 in-process 로 바뀌었으므로 둘 다 잴 수 있어야 한다.
    # `claude-code` 는 `arun` 이 없어 `mcp` 만 된다.
    parser.add_argument("--transport", default="mcp", choices=["mcp", "in-process"],
                        help="도구를 어떻게 붙일지. mcp=하위 프로세스(옛 측정 경로), "
                             "in-process=배송 경로")
    parser.add_argument("--model", default=None, help="런타임 모델")
    # 안 주면 CLI 기본값으로 돈다. `manifest.json` 에는 그 사실이 `None` 으로 남는다 —
    # 2026-07-27 측정들은 그 기록조차 없어서 어느 단계로 돌았는지 지금도 모른다 (D8).
    parser.add_argument("--effort", default=None,
                        choices=["low", "medium", "high", "xhigh", "max"],
                        help="claude-code 사고 노력 단계. 안 주면 CLI 기본값")
    parser.add_argument("--dry-run", action="store_true",
                        help="AI 서버 응답을 기록하되 파일에 반영하지 않는다")
    parser.add_argument("--via-api", default=None, metavar="URL",
                        help="런타임을 직접 부르지 않고 AI 서버를 HTTP 로 부른다 "
                             "(선택→변환 2단계). 예: http://127.0.0.1:8000")
    parser.add_argument("--internal-api-key", default=None,
                        help="--via-api 의 X-Internal-API-Key. 기본은 환경변수 INTERNAL_API_KEY")
    parser.add_argument("--from-experiment", default=None, metavar="SLUG",
                        help="기존 실험의 data/ 를 복사해 시작점으로 쓴다 (원본은 보존)")
    args = parser.parse_args()

    if not args.sources:
        parser.error("원본문서 파일 경로가 최소 1개 필요하다")

    if args.effort and args.via_api:
        # `--via-api` 는 AI 서버가 자기 런타임으로 돈다. 여기서 준 `--effort` 는 어디에도
        # 안 걸리는데 `manifest.json` 에는 적힌다 — 거짓 기록이다.
        parser.error("--effort 는 --via-api 와 함께 쓸 수 없다. AI 서버를 띄울 때 정한다")

    warning = dry_run_warning(args.dry_run, args.from_experiment)
    if warning:
        print(warning, file=sys.stderr)

    sources = [Path(s).resolve() for s in args.sources]
    missing = [str(s) for s in sources if not s.is_file()]
    if missing:
        parser.error(f"없는 파일: {', '.join(missing)}")

    experiment = Experiment(args.experiment) if args.experiment else None
    if experiment:
        # Written before the run, not after: an hour-long run that dies partway
        # still leaves a record of what it was and which code it used.
        try:
            manifest = experiment.start(
                runtime=args.runtime,
                model=load_runtime(args.runtime, args.model, effort=args.effort).model,
                scope=args.scope,
                corpus=[s.name for s in sources],
                purpose=args.purpose or "(기록 없음)",
                dry_run=args.dry_run,
                effort=args.effort,
            )
        except FileExistsError as exc:
            parser.error(str(exc))
        print(f"실험 {experiment.slug}  guide {manifest['code']['guideSha']}  "
              f"모델 {manifest['model']}")
        if args.from_experiment:
            from experiment import copy_from

            copy_from(args.from_experiment, experiment)
            print(f"{args.from_experiment} 의 data/ 를 복사해 시작한다")
        root = experiment.data
    else:
        root = resolve_root(None, args.root)
        root.mkdir(parents=True, exist_ok=True)

    async def run() -> dict:
        if not args.via_api:
            return await _run_batch(root, args.scope, sources, args.runtime, args.model,
                                    dry_run=args.dry_run, effort=args.effort,
                                    transport=args.transport)
        key = args.internal_api_key or os.environ.get("INTERNAL_API_KEY", "")
        if not key:
            print("경고: 내부 API 키가 없다 — AI 서버가 모든 요청을 401 로 막는다 "
                  "(--internal-api-key 또는 INTERNAL_API_KEY)", file=sys.stderr)
        async with api_client(args.via_api, key) as client:
            # `--via-api` 는 AI 서버가 자기 런타임으로 돈다 — `effort` 는 그 서버를 띄울
            # 때 정해지므로 여기서 넘길 수 없다. 그 경로로 잰 값은 서버 쪽 설정에 달렸다.
            return await _run_batch(root, args.scope, sources, args.runtime, args.model,
                                    dry_run=args.dry_run, client=client)

    report = asyncio.run(run())

    counts: dict[str, int] = {}
    for d in report["documents"]:
        counts[d["outcome"]] = counts.get(d["outcome"], 0) + 1
    committed = sum(len(d.get("committed") or []) for d in report["documents"])
    summary = " · ".join(f"{k} {v}" for k, v in sorted(counts.items()))
    print(f"\n=== 문서 {len(report['documents'])}건 [{summary}], 반영 {committed}건 ===")
    for d in report["documents"]:
        if d["outcome"] == "failed":
            print(f"  {d['document']}: {d['error']}")

    if experiment:
        experiment.finish(report)
        print(f"결과: {experiment.report_path}\n메모:  {experiment.notes_path}")
    elif args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"결과: {args.report}")


if __name__ == "__main__":
    main()
