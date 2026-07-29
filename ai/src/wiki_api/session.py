"""요청 1건의 생애.

임시 루트를 열고, **요청이 실어 온** 위키로 라이브 층을 채우고, 에이전트를 돌리고,
폐기한다. `/data/ajt` 를 만지지 않는다 — 반영은 Spring 이 `work/{jobId}` 에서 수행한다
(DR-007·008).

v1.1.0 에서 이 파일의 성격이 바뀌었다: **Spring 에 되묻지 않는다.** 하이드레이션 재료
(`pages`·`index_markdown`)는 요청 본문의 `selectedWikis`·`currentIndex` 에서 오고, 원본문서
본문도 요청이 준다. HTTP 왕복 0회 — `SpringClient` 는 여기서 사라졌다 (설계 §1·§2).

`lint` 를 여기서 다시 부르는 이유: 에이전트가 부르고 통과했다고 말해도 그 말을 믿지 않는다.
반영 전 기계 검증이 「승인 게이트 없음」을 성립시키는 유일한 장치다.
"""

from __future__ import annotations

import asyncio
import re
import shutil
import tempfile
from pathlib import Path

from agent_runtime.limits import exceeds_ceiling
from wiki_mcp.vaultfs import INDEX_ADDRESS, LocalVaultFS, SpringVaultFS

from .errors import FailureStage, InternalError

# `LintHandler.collect()` 가 `LintIssue` 목록을 준다. 앞 판본은 `run()` 의 마크다운
# 보고서를 정규식 3개로 되파싱했는데 두 가지가 깨졌다:
#
#   * `_MAX_PER_GROUP` 절단 — 보고서는 group 당 40건만 찍고 나머지를 "... N건 더" 로
#     접는다. 라이브 전용 error 40건이 앞을 채우면 작업 층 error 가 보고서에서 사라지고
#     게이트가 조용히 통과했다(fail-open). 주소별로 나눠 돌려 우회하고 있었다
#   * 문장 결합 — `tools/lint.py` 의 한국어 문장을 다듬으면 여기 정규식이 안 맞고,
#     그러면 막아야 할 것을 막지 않는다. 테스트가 그것을 잡지 못한다
#
# 이제 코드·주소·각주 라벨·링크 대상을 구조화된 필드로 받는다.

# 본문에서 각주 정의 줄을 찾는다 — `tools/lint.py` 의 `_FOOTNOTE_DEF_RE` 와 같은 모양이다.
_FOOTNOTE_DEF_RE = re.compile(r"^\[\^([^\]]+)\]:\s*(.+)$", re.MULTILINE)

# `index.md` 에 대해서만 무시하는 코드들. Spring 이 하이드레이션에서 주는
# `indexMarkdown`은 목차 본문일 뿐 frontmatter를 갖지 않는다 — 운영 중인 목차가
# 이미 그렇게 저장돼 있어서다. dangling-link·citation 류는 여기 포함하지 않는다 —
# 이번 요청이 목차를 고쳤다면 그 안의 깨진 링크·인용은 그대로 반영을 막아야 한다.
_INDEX_FRONTMATTER_CODES = frozenset({
    "missing-frontmatter", "missing-title", "missing-tags",
    "missing-category", "footnote-in-frontmatter",
})

# 라이브 전용(= 이번 요청이 건드리지 않은) 페이지에서 **막는** 코드. 하나뿐이다.
#
# 하이드레이션은 이 범위의 위키 페이지 전부를 올리지만 원본문서는 **이번 요청의 것만**
# 올린다 (`load_source`/`stage_source`). 그래서 다른 문서를 인용하는 기존 페이지의 각주는
# 구조적으로 풀리지 않고, frontmatter 도 Spring 이 준 그대로다. 각주 정의 누락·중복도
# 마찬가지로 이번 요청이 만든 것이 아니다 — 어느 쪽도 이 요청으로 고칠 수 없으므로 막으면
# 그 범위가 영구히 반영 불능이 된다.
#
# `dangling-link` 만 예외이고, 그것도 **이번 요청이 원인일 때만** 막는다: 링크 대상이
# 작업 층 주소면(= 이번 요청이 그 페이지를 지웠거나 병합했다) 우리가 깬 것이다. 그 밖의
# 깨진 링크는 하이드레이션이 부분적이라는 사실의 부산물일 수 있으므로 막지 않는다.
_LIVE_ONLY_BLOCKING_CODES = frozenset({"dangling-link"})

# 요청 1건씩만 세션을 연다. `vaultfs/local.py` 의 연결이 모듈 전역(`LocalVaultFS.open`/
# `close` 가 프로세스 하나의 연결을 잡는다)이라, 두 요청이 겹치면 뒤에 들어온 요청의
# `open` 이 앞 요청의 연결을 덮고 `close` 가 남의 연결을 닫는다 — 임시 색인이 서로 섞인다.
#
# FR-AI-003 의 「순차 처리」와 같은 방향이지만 같은 것은 아니다: 그쪽은 업로드 묶음 안
# 문서를 1건씩 처리하라는 도메인 규칙이고 순서를 정하는 것은 Spring 이다. 이 잠금은 그와
# 무관하게 프로세스 안 자원이 하나뿐이라서 필요한 것이며, 결과적으로 AI 서버는 요청을
# 전역 직렬 큐로 처리한다. 병렬이 필요해지면 잠금을 없애는 것이 아니라 `local.py` 의
# 연결을 세션 단위로 바꾼 뒤에 없애야 한다.
_SESSION_LOCK = asyncio.Lock()

# 런타임이 `RunResult.error` 문장에 남기는 실패의 성격. 예외가 아니라 문자열로 오므로
# (CLI 는 오류에도 종료 코드 0 이고, DeepAgents 어댑터는 예외를 문장으로 바꾼다) 여기서
# 단계로 되돌린다 (설계 4.5).
_START_MARKERS = ("MCP 툴이 빠졌다", "마지막 도달 단계: 시작 전", "MCP 서버")
_TIMEOUT_MARKERS = ("안에 끝나지 않았다",)


def failure_stage_for_error(error: str) -> FailureStage:
    if any(marker in error for marker in _TIMEOUT_MARKERS):
        return FailureStage.AGENT_TIMEOUT
    if any(marker in error for marker in _START_MARKERS):
        return FailureStage.AGENT_START
    return FailureStage.AGENT_ERROR


def assert_within_ceiling(text: str, error_code: str) -> None:
    """이 문서가 시간 천장(30분) 안에 끝날 수 있는 크기인가 (설계 §1).

    36k자쯤에서 갈린다 — `runtime/limits.py` 의 회귀식이 예측하는 시간에 안전계수 1.5 를
    곱한 값이 천장을 넘는 지점이다. 넘으면 **접수 시점에 실패**한다. 30분을 기다린 뒤
    `agent_timeout` 으로 알리는 것은 같은 결과를 30분 늦게 주는 것이고, 그 30분 동안
    전역 직렬 큐(`_SESSION_LOCK`)가 막힌다.

    그래서 세션 **밖**에서 부른다 — 실패가 확정된 요청이 잠금과 임시 루트를 먼저 잡을
    이유가 없다. 단계는 `context_load` 다: 에이전트는 시작도 하지 않았다.
    """
    if exceeds_ceiling(len(text)):
        raise InternalError(
            error_code,
            f"문서가 너무 큽니다 ({len(text):,}자) — 제한 시간(30분) 안에 처리할 수 "
            f"없습니다. 문서를 나눠 올려 주세요.",
            FailureStage.CONTEXT_LOAD)


class WikiSession:
    def __init__(self, *, scope_key: str, job_id: str | None, request_id: str,
                 runtime, pages: list[dict] | None = None,
                 index_markdown: str = "",
                 error_code: str = "WIKI_TRANSFORMATION_FAILED"):
        self.scope_key = scope_key
        self.job_id = job_id or f"req-{request_id}"
        self.request_id = request_id
        self.runtime = runtime
        # 라이브 층의 재료. Spring 이 2단계 요청에 실어 보낸 것이 전부다 — 여기 없는
        # 위키는 이번 변환에서 **보이지 않고, 따라서 변하지 않는다** (설계 §1 의
        # 감수된 품질 리스크).
        self.pages = pages or []
        self.index_markdown = index_markdown
        self.error_code = error_code
        self._root: Path | None = None
        self._locked = False
        self.scope_id: str = ""
        self.fs: SpringVaultFS | None = None
        # 에이전트가 돌기 전 라이브 페이지의 인용 관계. 각주를 떨어뜨린 것을 `unlink` 로
        # 내려면 (I4) 고치기 전 상태가 필요하다 — `document_references` 는 층이 없어서
        # 에이전트가 쓰는 순간 옛 간선이 사라진다.
        self.live_citations: dict[str, set[str]] = {}

    async def __aenter__(self) -> "WikiSession":
        # 모듈 주석 참고: 임시 색인 연결이 프로세스 전역이라 세션이 겹치면 서로 덮는다.
        await _SESSION_LOCK.acquire()
        self._locked = True
        # `acquire()` 뒤부터는 전부 이 try 안이다. `__aenter__` 가 예외로 빠지면 `async with`
        # 본문이 시작되지 않아 `__aexit__` 도 불리지 않는다 — 여기서 놓지 않으면 잠금이 남고
        # `acquire()` 에 timeout 이 없으므로 그 뒤 모든 요청이 영구히 매달린다(서버가 죽지
        # 않고 조용히 멈춘다). `BaseException` 까지 잡는 이유는 `CancelledError` 다: 클라이언트
        # 가 하이드레이션 중 끊으면 uvicorn 이 태스크를 취소하고, 그것은 `Exception` 이 아니다.
        try:
            self._root = Path(tempfile.mkdtemp(prefix="ajt-ai-"))
            try:
                self.scope_id = await SpringVaultFS.open(
                    self._root, self.scope_key, self.job_id,
                    pages=self.pages, index_markdown=self.index_markdown)
            except InternalError:
                raise
            except Exception as exc:
                # 이제 네트워크가 없으므로 여기서 터지는 것은 요청이 준 위키 dict 가
                # 계약과 다른 모양이거나 임시 색인이 열리지 않은 경우다. 어느 쪽이든
                # 에이전트 이전 단계다 — Spring 이 `document_results` 에 「에이전트 오류」로
                # 적지 않게 단계를 붙여 보낸다 (NFR-AI-003).
                raise InternalError(self.error_code,
                                    f"현재 Wiki를 불러올 수 없습니다 — {exc}",
                                    FailureStage.CONTEXT_LOAD) from exc
            self.fs = SpringVaultFS(self.scope_key, self.job_id)
        except BaseException:
            await self._teardown()
            raise
        return self

    async def __aexit__(self, *_exc) -> None:
        await self._teardown()

    async def _teardown(self) -> None:
        try:
            await LocalVaultFS.close()
            if self._root and self._root.exists():
                shutil.rmtree(self._root, ignore_errors=True)
        finally:
            if self._locked:
                self._locked = False
                _SESSION_LOCK.release()

    async def load_source(self, document_id: str, text: str,
                          original_file_name: str | None = None) -> str:
        """이번 요청의 원본문서를 라이브 층에 올리고 주소를 돌려준다.

        v1.1.0 이전에는 이 이름이 「Spring 에서 파싱 본문을 받아온다」였다. 이제 본문은
        요청이 주므로 `stage_source` 와 같은 일이고, 이름만 호출부의 의미(= 이 작업의
        대상 문서를 문맥에 올린다)를 위해 남긴다.
        """
        return await self.stage_source(document_id, text, original_file_name)

    async def stage_source(self, document_id: str, text: str,
                           original_file_name: str | None = None) -> str:
        """요청이 준 원본문서를 라이브 층에 올린다.

        `lint` 가 각주 인용문을 이 본문과 문자열 대조한다 (NFR-AI-002). 파일명이 없으면
        `stage_source` 가 `document-{id}` 를 쓴다 — 각주는 파일명으로 문서를 가리키므로
        그 이름이 곧 에이전트가 각주에 적을 이름이 된다.
        """
        return await self.fs.stage_source(self.scope_id, document_id, text,
                                          original_file_name)

    async def run_agent(self, instruction: str, limit_seconds: int):
        """에이전트를 돌리고 결과를 검사한다.

        시간 상한이 두 곳에 있으면 안 된다. `asyncio.to_thread` 로 띄운 sync 런타임은
        **취소할 수 없다** — `wait_for` 가 풀려도 스레드는 계속 돌고 하위 프로세스도 살아
        있다. 그래서 sync 런타임에는 `wait_for` 를 걸지 않고 계산된 상한을 런타임에 넘긴다
        (CLI 는 subprocess timeout, DeepAgents 는 자기 루프의 wait_for). async 런타임은
        취소가 실제로 먹으므로 `wait_for` 를 유지한다.

        그리고 **`RunResult.error` 를 반드시 본다.** 두 런타임 모두 실패를 예외가 아니라
        `error` 문장으로 돌려준다 — 그것을 안 보면 실패한 작업이 200 「변경 없음」으로 나가
        Spring 이 성공으로 기록한다 (NFR-AI-003).
        """
        # 에이전트가 쓰기 전에 찍는다 — 각주가 사라진 것을 나중에 알 방법이 이것뿐이다 (I4).
        from .changes import snapshot_citations

        self.live_citations = await snapshot_citations(self.fs, self.scope_id)
        try:
            if hasattr(self.runtime, "arun"):
                result = await asyncio.wait_for(
                    self.runtime.arun(instruction, fs=self.fs, scope_id=self.scope_id,
                                      root=self._root, scope_key=self.scope_key,
                                      job_id=self.job_id),
                    timeout=limit_seconds)
            else:
                result = await asyncio.to_thread(
                    self.runtime.run, instruction, root=self._root,
                    scope_key=self.scope_key, job_id=self.job_id,
                    timeout=limit_seconds)
        except asyncio.TimeoutError as exc:
            raise InternalError(
                self.error_code,
                f"문서 분석이 제한 시간({limit_seconds}초)을 초과했습니다.",
                FailureStage.AGENT_TIMEOUT) from exc
        except InternalError:
            raise
        except Exception as exc:
            raise InternalError(self.error_code, f"에이전트 실행에 실패했습니다 — {exc}",
                                FailureStage.AGENT_ERROR) from exc

        error = getattr(result, "error", None)
        if error:
            raise InternalError(self.error_code, f"에이전트 실행에 실패했습니다 — {error}",
                                failure_stage_for_error(str(error)))
        return result

    async def assert_lint_clean(self) -> None:
        """반영 전 기계 검증. error 가 남으면 이 문서분을 반영하지 않는다.

        한 번만 돌린다. 앞 판본이 주소별로 나눠 돌린 것은 보고서 절단(`_MAX_PER_GROUP`)을
        피하려는 우회였고, `collect()` 는 절단하지 않으므로 필요 없다.

        막는 기준은 그대로다:

          * 작업 층 주소 — 모든 error 가 막는다. 단 목차의 frontmatter 계열과 실행 전부터
            있던 각주(`_is_legacy_footnote`)는 뺀다
          * 라이브 전용 주소 — `_LIVE_ONLY_BLOCKING_CODES` 만, 그것도 링크 대상이 이번
            작업이 건드린 주소일 때만 막는다
          * `uncited-source` 는 버린다 (FR-DOC-012) — 정당한 무변경 재투입이 있다.
            에이전트에게는 보이고 게이트는 통과시킨다
        """
        from wiki_mcp.tools.lint import LintHandler, LintIssue

        scope_row = {"id": self.scope_id, "scope_key": self.scope_key}
        work_addresses = {change["address"]
                          for change in await self.fs.pending_changes(self.scope_id)}

        blocking: list[LintIssue] = []
        for issue in await LintHandler(self.fs, scope_row).collect(pattern="*",
                                                                  include_graph=True):
            if issue.severity != "error" or issue.code == "uncited-source":
                continue
            if issue.address in work_addresses:
                if issue.address == INDEX_ADDRESS and issue.code in _INDEX_FRONTMATTER_CODES:
                    # Spring 이 주는 목차 본문에는 frontmatter 가 없다 — 에이전트가 목차를
                    # 고쳐도 그 사실은 변하지 않는다. dangling-link·인용은 예외가 아니다.
                    continue
                if issue.code == "unresolved-citation" and \
                        await self._is_legacy_footnote(issue.address, issue.footnote):
                    continue
                blocking.append(issue)
                continue
            # 라이브 전용 페이지.
            if issue.code not in _LIVE_ONLY_BLOCKING_CODES:
                continue
            if issue.link_target in work_addresses:
                blocking.append(issue)

        if blocking:
            first = blocking[0]
            raise InternalError(
                self.error_code,
                f"검증에 실패했습니다 — [{first.code}] `{first.address}` — {first.message}",
                FailureStage.LINT_FAILED)

    async def _is_legacy_footnote(self, address: str, label: str | None) -> bool:
        """이 각주 정의가 에이전트 실행 **전부터** 그 페이지에 있었나 (F1).

        미해결 인용을 파일명으로 가려내려던 앞 판본은 죽은 분기였다 — `find_source` 가
        파일명·확장자 없는 이름·`source_id`·주소를 모두 매칭하므로, 이번 요청이 올린
        문서를 가리키는 각주는 애초에 `unresolved-citation` 까지 오지 않는다. 그 규칙은
        결과적으로 **모든** 미해결 인용을 버렸고, 지어낸 인용이 게이트를 통과했다
        (NFR-AI-002 상실).

        그래서 이름이 아니라 **나이**로 판정한다. 에이전트 쓰기는 작업 층으로만 가므로
        라이브 층 행은 실행 전 상태 그대로다. 같은 각주 정의 줄이 라이브 본문에 그대로
        있으면 그 각주는 처음 쓰일 때 검증된 것이고 지금 원문이 없을 뿐이다 — 통과시킨다.
        새로 쓰거나 고친 각주 정의는 라이브에 없으므로 그대로 막는다.

        라벨은 `LintIssue.footnote` 로 받는다. 앞 판본은 메시지 문장을 정규식으로 뜯었고,
        `tools/lint.py` 의 한국어 문장이 바뀌면 라벨을 못 찾아 **모든** 미해결 인용을
        막는 쪽으로 조용히 넘어갔다.
        """
        if not label:
            return False
        current = (await self.fs.get(self.scope_id, address) or {}).get("content") or ""
        definition = self._definition_line(current, label)
        if not definition:
            return False
        live = await self.fs.live_content(self.scope_id, address)
        return definition in self._definitions(live or "")

    @staticmethod
    def _definitions(content: str) -> set[str]:
        return {f"[^{fid}]: {raw.strip()}" for fid, raw in _FOOTNOTE_DEF_RE.findall(content)}

    @staticmethod
    def _definition_line(content: str, label: str) -> str | None:
        for fid, raw in _FOOTNOTE_DEF_RE.findall(content):
            if fid == label:
                return f"[^{fid}]: {raw.strip()}"
        return None

    async def address_for_wiki_id(self, wiki_id: str) -> str:
        """`wikiId` → 주소. 요청이 실어 온 위키에 없으면 400.

        이 세션이 아는 위키는 요청 본문에 실려 온 것뿐이다 — 단건 보충 조회가 없으므로
        되물을 곳도 없다. 그래서 없는 `wikiId` 는 서버 상태가 아니라 **요청값의 문제**다.

        404 가 아닌 이유는 계약(v1.3.0)이다. 엔드포인트마다 400·401·500 만 정의하고 400 을
        "지시 내용 또는 Wiki 컨텍스트 오류"로 둔다. 404 를 내면 Spring 의 상태 분기에서
        `UNEXPECTED_STATUS` 로 떨어져 어떤 실패인지 알 수 없게 된다.
        """
        for row in await self.fs.list_documents(self.scope_id):
            if str(row.get("wiki_id") or "") == str(wiki_id):
                return row["address"]
        raise InternalError("INVALID_WIKI_EDIT_REQUEST",
                            "요청에 실린 Wiki 컨텍스트에서 대상 Wiki를 찾을 수 없습니다.",
                            status=400)
