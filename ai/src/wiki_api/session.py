"""요청 1건의 생애.

임시 루트를 열고, **Wiki 조회 API 로** 라이브 층을 채우고, 에이전트를 돌리고, 폐기한다.
`/data/ajt` 를 만지지 않는다 — 반영은 Spring 이 `work/{jobId}` 에서 수행한다
(DR-007·008).

S15P11B106-175 에서 push 경로가 사라졌다. v1.1.0~1.8.0 사이에는 요청이 실어 온
`selectedWikis`·`currentIndex` 로 라이브 층을 채웠는데, 그 경로는 백엔드가 고른 몇 장만
보이는 부분 가시성이었고 이제 `FederatedVaultFS` 가 범위 전체를 조회 API 로 읽는다.

`lint` 를 여기서 다시 부르는 이유: 에이전트가 부르고 통과했다고 말해도 그 말을 믿지
않는다. 반영 전 기계 검증이 「승인 게이트 없음」을 성립시키는 유일한 장치다.
"""

from __future__ import annotations

import asyncio
import re
import shutil
import tempfile
from pathlib import Path

from agent_runtime.base import runs_tools_in_process
from agent_runtime.guards import wrote_without_reading
from agent_runtime.limits import exceeds_ceiling
from wiki_mcp.vaultfs import (INDEX_ADDRESS, FederatedVaultFS, LocalVaultFS,
                              SpringVaultFS)
from wiki_mcp.vaultfs.query_client import (QueryBudgetExceeded, ScopeChangedError,
                                           WikiQueryClient)

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


def _promote_gateway_call(exc: Exception, error_code: str, message: str) -> InternalError:
    """핸들러 본문의 조회 API 호출(`run_agent` 밖) 실패를 단계 있는 `InternalError` 로 승격한다.

    `__aenter__`·`run_agent` 가 이미 하는 네 갈래(설계 4.5)와 같은 모양이다: 여기서
    새로 잡는 두 호출(`get_citation_backlinks`·`stage_evidence_documents`)도 조회 API
    HTTP 를 부르므로 `ScopeChangedError`·`QueryBudgetExceeded`·그 밖의 실패가 그대로
    새면 `errors.py` 마지막 그물이 `failureStage` 없이 500 을 낸다. 그 밖의 예외는
    `run_agent` 와 달리 `context_load` 로 보낸다 — 에이전트가 아직 시작하기 전(변환의
    backlink 계산)이거나 본문을 대는 준비 단계(수정의 근거 문서 적재)이기 때문이다.
    """
    if isinstance(exc, ScopeChangedError):
        return InternalError(error_code, str(exc), FailureStage.SCOPE_CHANGED)
    if isinstance(exc, QueryBudgetExceeded):
        return InternalError(error_code, str(exc), FailureStage.AGENT_ERROR)
    return InternalError(error_code, f"{message} — {exc}", FailureStage.CONTEXT_LOAD)


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
    """경로는 하나다 — 라이브 층은 언제나 Wiki 조회 API(`FederatedVaultFS`)에서 온다.

    S15P11B106-175 이전에는 `wiki_capability`·`scope_version`·`backend_base_url` 셋이
    다 있을 때만 조회 API 경로였고, 하나라도 없으면 요청이 실어 온 `selectedWikis` 로 채우는
    과도기 push 경로였다. 그 경로가 사라졌으므로 셋은 이제 **없으면 하이드레이션이
    실패하는 필수 재료**다 — 스키마가 앞의 둘을 필수로 막고(`schemas.py`), 세 번째는
    기동 시점에 `serve.py` 가 막는다.

    `request_id`·`runtime` 에 기본값이 있는 이유는 배관만 보는 테스트다 (`request_id` 는
    `job_id` 대체값과 `X-Request-Id` 전달에만, `runtime` 은 `run_agent` 에만 쓰인다).
    """

    def __init__(self, *, scope_key: str, job_id: str | None,
                 request_id: str = "", runtime=None,
                 error_code: str = "WIKI_TRANSFORMATION_FAILED",
                 requires_existing_wiki: bool = False,
                 wiki_capability: str | None = None,
                 scope_version: int | None = None,
                 backend_base_url: str | None = None,
                 internal_api_key: str | None = None,
                 query_transport=None):
        self.scope_key = scope_key
        self.job_id = job_id or f"req-{request_id}"
        self.request_id = request_id
        self.runtime = runtime
        self.error_code = error_code
        # 삭제·교체는 그 문서로 만든 위키가 이미 있다는 전제다 (설계 4.1).
        self.requires_existing_wiki = requires_existing_wiki
        # 조회 API 경로의 재료. **`wiki_capability` 는 로그·예외 메시지·telemetry 에 남기지
        # 않는다** (계약 1.6.0 의 마스킹 요구). 헤더로만 나간다 — `query_client.py`.
        self.wiki_capability = wiki_capability
        self.scope_version = scope_version
        self.backend_base_url = backend_base_url
        self.internal_api_key = internal_api_key
        # 테스트용 httpx transport. 프로덕션에서는 None 이다.
        self.query_transport = query_transport
        self._query_client: WikiQueryClient | None = None
        self._root: Path | None = None
        self._locked = False
        self.scope_id: str = ""
        self.fs: SpringVaultFS | None = None

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
            self._assert_runtime_can_use_the_gateway()
            self._root = Path(tempfile.mkdtemp(prefix="ajt-ai-"))
            try:
                self._query_client = WikiQueryClient(
                    self.backend_base_url, api_key=self._internal_api_key(),
                    capability=self.wiki_capability, scope_key=self.scope_key,
                    scope_version=self.scope_version,
                    request_id=self.request_id or None,
                    transport=self.query_transport)
                self.scope_id = await FederatedVaultFS.open(
                    self._root, self.scope_key, self.job_id,
                    client=self._query_client)
                self.fs = FederatedVaultFS(self.scope_key, self.job_id,
                                           self._query_client)
                self._assert_the_scope_has_wikis_if_it_must()
            except InternalError:
                raise
            except ScopeChangedError as exc:
                # 하이드레이션 중 버전이 어긋났다. `context_load` 로 내보내면 Spring 이
                # 「문맥 적재 실패」로 기록하는데, 실제 원인은 그 사이 위키가 바뀐 것이고
                # 재시도로 풀린다 — 단계가 다르면 사람이 다른 곳을 본다 (DR-030).
                raise InternalError(self.error_code, str(exc),
                                    FailureStage.SCOPE_CHANGED) from exc
            except Exception as exc:
                # 조회 API 가 안 뜨거나 응답이 계약과 다른 모양이거나 임시 색인이 열리지 않은
                # 경우다. 어느 쪽이든 에이전트 이전 단계다 — Spring 이 `document_results`
                # 에 「에이전트 오류」로 적지 않게 단계를 붙여 보낸다 (NFR-AI-003).
                raise InternalError(self.error_code,
                                    f"현재 Wiki를 불러올 수 없습니다 — {exc}",
                                    FailureStage.CONTEXT_LOAD) from exc
        except BaseException:
            await self._teardown()
            raise
        return self

    async def __aexit__(self, *_exc) -> None:
        await self._teardown()

    @property
    def live_page_count(self) -> int:
        """하이드레이션이 받은 라이브 위키 장수. 아직 안 열렸으면 0 이다."""
        return getattr(self.fs, "page_count", 0)

    def _assert_the_scope_has_wikis_if_it_must(self) -> None:
        """삭제·교체·수정인데 위키가 0장이면 모순이다 (설계 4.1).

        조회 API 는 오류 없이 빈 목록을 줄 수 있다. HTTP 실패·404·버전 불일치는 예외로
        갈리지만 200 에 빈 배열은 안 갈린다 — 신규 범위면 정상이고, 범위키가 틀렸거나
        권한이 어긋나면 사고인데 응답이 같다.

        지울 문서가 있다는 것, 고칠 위키가 있다는 것은 둘 다 그 범위에 위키가 이미
        있었다는 뜻이다. 그래서 삭제·교체·수정에서만 0장을 막는다. 추가는 0장이
        정상이다 (첫 문서).

        `selectedWikis` 가 없어지면서 접수 시점 검증(I3)이 설 자리를 잃었고, 그 판단을
        여기로 옮긴 것이다. 에이전트를 돌리기 전이므로 단계는 `context_load` 다.

        `wiki-edits` 도 `requires_existing_wiki=True` 로 이 경로를 타므로 메시지가
        「삭제·교체」로 고정돼 있으면 수정 요청의 실패가 엉뚱하게 번역된다
        (`error_code` 로 갈라 `wiki-edits` 는 EDIT 문구를 낸다).
        """
        if not (self.requires_existing_wiki and self.live_page_count == 0):
            return
        if self.error_code == "WIKI_EDIT_FAILED":
            message = ("이 범위에 위키가 없습니다 — 수정할 위키가 있어야 합니다. "
                       "범위 설정이나 열람 허가를 확인해 주세요.")
        else:
            message = ("이 범위에 위키가 없습니다 — 삭제·교체할 원본문서로 만든 위키가 "
                       "있어야 합니다. 범위 설정이나 열람 허가를 확인해 주세요.")
        raise InternalError(self.error_code, message, FailureStage.CONTEXT_LOAD)

    def _assert_runtime_can_use_the_gateway(self) -> None:
        """Wiki 조회 API 는 도구가 이 프로세스 안에서 도는 런타임에서만 성립한다.

        S15P11B106-175 이전에는 조회 API 를 쓰는 요청만 이 검사를 받았다 — 요청이 위키를 실어 오는
        push 경로가 남아 있어 하위 프로세스 런타임도 일할 수 있었다. 그 경로가 사라져
        이제는 **모든** 위키 요청이 여기를 지난다.

        별도 프로세스의 MCP 서버는 `fs_factory`(`wiki_mcp/local_server.py`)가
        `LocalVaultFS` 를 만든다. 조회 API 클라이언트가 그쪽에 없으므로 두 가지가 동시에
        깨진다:

          * **본문이 없다.** 조회 API 하이드레이션은 카탈로그만 채우고 본문은 `get()` 이
            요구할 때 당기는데, 그 `get()` 은 이쪽 프로세스에만 있다. 에이전트는 제목만
            있는 빈 페이지를 보고 "내용이 없다" 고 판단해 라이브를 덮는다
          * **중단 신호가 없다.** 범위 변경을 프로세스 밖으로 넘길 길이 없다
            (`_raise_if_scope_changed` 의 한계 항목)

        판정은 `arun` 유무다 (`runs_tools_in_process`). `deepagents` 가 그것을 구현하면서
        (S15P11B106-152) 배송 경로가 열렸고, `claude-code` 는 CLI 하위 프로세스라
        구현할 수 없으므로 계속 거절된다. 거절이 조용히 빈 위키를 만드는 것보다 낫다.
        """
        if not runs_tools_in_process(self.runtime):
            raise InternalError(
                self.error_code,
                "이 서버 구성에서는 Wiki 조회 API 를 쓸 수 없습니다 — 에이전트 런타임이 "
                "MCP 서버를 별도 프로세스로 띄우므로 조회 API 본문이 에이전트에 닿지 "
                "않습니다. AI 서버를 도구가 같은 프로세스에서 도는 런타임"
                "(`AI_RUNTIME=deepagents`)으로 띄워 주세요.",
                FailureStage.CONTEXT_LOAD)

    def _internal_api_key(self) -> str:
        """조회 API 호출에 붙일 서버 간 인증 키.

        Spring→AI 와 AI→Spring 이 같은 내부 키를 쓴다 (`API_컨벤션` 8.1). 라우터가
        `app.state.api_key` 를 넘겨 주므로 `serve.py --internal-api-key`(또는
        `INTERNAL_API_KEY`/`.env`)로만 준 경우에도 비지 않는다 — `ServerSettings` 가
        조립 지점(`serve.py`)에서 이미 그 우선순위를 병합했다. **여기서 `os.environ` 을
        다시 읽지 않는다** — 값이 여기 도달하지 못했다면 그것은 조립 지점의 버그이지,
        이 계층에서 되돌아갈 문제가 아니다.
        """
        return self.internal_api_key or ""

    async def _teardown(self) -> None:
        try:
            if self._query_client is not None:
                # 안 닫으면 커넥션이 남는다. 색인보다 먼저 닫는다 — 아래가 실패해도
                # 소켓은 놓는다.
                await self._query_client.aclose()
                self._query_client = None
                # 카탈로그 레지스트리(`federated._CATALOGS`)까지 비운다. 남기면 다음
                # 요청이 앞 스코프의 wikiId 표를 들고 돌 수 있다.
                await FederatedVaultFS.close()
            else:
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
        try:
            if hasattr(self.runtime, "arun"):
                result = await asyncio.wait_for(
                    self.runtime.arun(instruction, fs=self.fs, scope_id=self.scope_id,
                                      root=self._root, scope_key=self.scope_key,
                                      job_id=self.job_id, timeout=limit_seconds),
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
        except ScopeChangedError as exc:
            # `except Exception` 앞이어야 한다 — 순서가 바뀌면 일반 처리가 먼저 잡아
            # `agent_error` 로 나가고 Spring 이 재시도로 풀리는 실패를 에이전트 결함으로
            # 기록한다.
            raise InternalError(self.error_code, str(exc),
                                FailureStage.SCOPE_CHANGED) from exc
        except QueryBudgetExceeded as exc:
            raise InternalError(self.error_code, str(exc),
                                FailureStage.AGENT_ERROR) from exc
        except Exception as exc:
            raise InternalError(self.error_code, f"에이전트 실행에 실패했습니다 — {exc}",
                                FailureStage.AGENT_ERROR) from exc

        # 예외로 올라오지 않은 조회 API 중단을 여기서 잡는다. `RunResult.error` 보다 먼저 본다 —
        # 범위가 바뀐 실행은 무엇을 썼든 반영할 수 없으므로 그 사실이 더 구체적인 실패다.
        self._raise_if_scope_changed()
        error = getattr(result, "error", None)
        if error:
            raise InternalError(self.error_code, f"에이전트 실행에 실패했습니다 — {error}",
                                failure_stage_for_error(str(error)))
        return result

    def _raise_if_scope_changed(self) -> None:
        """조회 API 클라이언트가 기억한 범위 변경이 있으면 중단한다 (설계 2.4·2.6).

        **예외 전파만으로는 성립하지 않아서 있는 검사다.** `ScopeChangedError` 는
        `VaultError` 이고 툴 6곳이 `except VaultError: return f"오류: {exc}"` 로 잡는다
        (`tools/read.py:169`·`tools/search.py:248`·`delete.py:39,82`·`write.py:370`·
        `lint.py:456`). 에이전트는 그 문자열을 읽고 계속 작업하므로, 조회 API 가 이미 「이
        범위는 바뀌었다」고 답한 뒤에도 실행이 성공으로 끝날 수 있다.

        **한계 — 지금 이것으로 덮이는 것은 in-process 경로뿐이다.** `claude-code`·
        `deepagents` 는 MCP 서버를 별도 프로세스(`wiki_mcp.local_server`)로 띄우고 그
        프로세스의 `fs_factory` 는 `LocalVaultFS` 를 만든다 — 조회 API 클라이언트가 아예
        그쪽에 없으므로 남길 흔적도, 세션이 볼 상태도 없다. 그 경로까지 닫으려면
        `local_server` 가 조회 API 모드에서 `FederatedVaultFS` 를 만들고 중단을 프로세스
        경계 밖으로 (툴 응답의 신호·종료 코드·파일 신호 중 하나로) 넘겨야 한다.
        기록: `.superpowers/sdd/2026-07-30-wiki-query-federated-adapter/task-7-report.md`.
        """
        client = self._query_client
        change = getattr(client, "scope_change", None) if client else None
        if change is not None:
            raise InternalError(self.error_code, str(change),
                                FailureStage.SCOPE_CHANGED)

    def assert_the_agent_looked_at_the_wiki(self, result) -> None:
        """읽기 툴을 한 번도 안 부른 실행을 막는다 (설계 4.2).

        위키가 0장인 범위는 읽을 것이 없는 것이 정상이므로 건너뛴다 — 신규 범위에
        첫 문서를 넣는 경우다.
        """
        if not self.live_page_count:
            return
        if wrote_without_reading(getattr(result, "tool_calls", {}) or {}):
            raise InternalError(
                self.error_code,
                "현재 Wiki 를 읽지 않았습니다 — 에이전트가 조회 도구를 한 번도 "
                "부르지 않아 기존 Wiki 를 덮어쓸 수 있습니다.",
                FailureStage.AGENT_ERROR)

    async def stage_evidence_documents(self, wiki_id: str) -> int:
        """대상 위키가 근거로 쓴 원본문서를 조회 API 로 읽어 라이브 층에 올린다.

        `wiki-edits` 가 `evidenceDocuments` 를 실어 보내던 것을 대신한다
        (S15P11B106-175). 문서 목록은 하이드레이션이 받아둔 범위 관계에 있으므로
        추가 조회는 문서 본문뿐이다.

        올린 본문의 총 길이를 돌려준다 — 호출자가 시간 상한을 계산한다.

        조회 API 호출(`parsed_document`, 문서 수만큼)의 실패는 `_promote_gateway_call` 로
        단계를 붙여 올린다 — 이 메서드가 `__aenter__`·`run_agent` 의 `try` 밖에서
        불리므로, 승격하지 않으면 범위 변경도 500 으로만 나간다.
        """
        catalog = getattr(self.fs, "_catalog", None)
        address = await self.address_for_wiki_id(wiki_id)
        document_ids = (catalog.document_ids_by_address.get(address, [])
                        if catalog else [])
        total = 0
        try:
            for document_id in document_ids:
                body = await self._query_client.parsed_document(document_id)
                text = body.get("parsedMarkdown") or ""
                await self.stage_source(document_id, text,
                                        body.get("originalFileName"))
                total += len(text)
        except InternalError:
            raise
        except Exception as exc:
            raise _promote_gateway_call(
                exc, self.error_code, "근거 문서를 불러오지 못했습니다") from exc
        return total

    async def citation_backlinks(self, address: str) -> list[dict]:
        """`FederatedVaultFS.get_citation_backlinks` 를 단계 승격까지 포함해 부른다.

        삭제·교체 변환이 걷어낼 각주를 찾으려 이 문서를 인용하는 라이브 페이지를
        조회 API 로 읽는다(`_ensure_body` → `page_content()`, 인용 위키 수만큼 HTTP).
        `__aenter__`·`run_agent` 의 `try` 밖에서 불리므로 실패를 여기서 직접 승격한다.
        """
        try:
            return await self.fs.get_citation_backlinks(self.scope_id, address)
        except InternalError:
            raise
        except Exception as exc:
            raise _promote_gateway_call(
                exc, self.error_code, "인용 관계를 불러오지 못했습니다") from exc

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

        # 조회 API 경로에서는 `collect()` 가 본문을 당기므로(지연 적재) 여기서도 범위 변경을
        # 만날 수 있다. 그것은 검증 실패(`lint_failed`)가 아니다 — 단계를 갈라 준다.
        try:
            issues = await LintHandler(self.fs, scope_row).collect(
                pattern="*", include_graph=True)
        except ScopeChangedError as exc:
            raise InternalError(self.error_code, str(exc),
                                FailureStage.SCOPE_CHANGED) from exc
        except QueryBudgetExceeded as exc:
            raise InternalError(self.error_code, str(exc),
                                FailureStage.AGENT_ERROR) from exc

        blocking: list[LintIssue] = []
        for issue in issues:
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
        """`wikiId` → 주소. 하이드레이션이 받은 카탈로그에 없으면 400.

        이 세션이 아는 위키는 조회 API 가 이 허가로 돌려준 범위 전체다. 거기 없다는 것은
        그 위키가 지워졌거나 이 허가의 범위 밖이라는 뜻이므로, 서버 상태가 아니라
        **요청값의 문제**다.

        404 가 아닌 이유는 계약(v1.3.0)이다. 엔드포인트마다 400·401·500 만 정의하고 400 을
        "지시 내용 또는 Wiki 컨텍스트 오류"로 둔다. 404 를 내면 Spring 의 상태 분기에서
        `UNEXPECTED_STATUS` 로 떨어져 어떤 실패인지 알 수 없게 된다.

        `list_documents` 자체는 하이드레이션이 채운 카탈로그를 읽는 로컬 조회지만, 이
        메서드는 `__aenter__`·`run_agent` 의 `try` 밖(`wiki-edits` 핸들러 본문)에서
        불린다 — 실패가 나면 `_promote_gateway_call` 로 단계를 붙인다. 그 뒤에 raise 하는
        400 (`INVALID_WIKI_EDIT_REQUEST`, 대상 위키를 못 찾음)은 그대로 통과시킨다 —
        서버 쪽 실패가 아니라 요청값 문제이기 때문이다.
        """
        try:
            documents = await self.fs.list_documents(self.scope_id)
        except Exception as exc:
            raise _promote_gateway_call(
                exc, self.error_code, "Wiki 목록을 불러오지 못했습니다") from exc
        for row in documents:
            if str(row.get("wiki_id") or "") == str(wiki_id):
                return row["address"]
        raise InternalError("INVALID_WIKI_EDIT_REQUEST",
                            "요청에 실린 Wiki 컨텍스트에서 대상 Wiki를 찾을 수 없습니다.",
                            status=400)

    async def content_length_for(self, address: str) -> int:
        """이 주소의 현재 본문 길이. 시간 상한 계산용이다 (`wiki-edits`).

        `self.fs.get` 이 `_ensure_body` 를 타서 본문을 조회 API 로 당긴다
        (`FederatedVaultFS._ensure_body`) — `stage_evidence_documents`·
        `citation_backlinks` 와 같은 자리(핸들러 본문, 세션 `try` 밖)에서 불리므로 같은
        이유로 `_promote_gateway_call` 을 거친다.
        """
        try:
            row = await self.fs.get(self.scope_id, address)
        except InternalError:
            raise
        except Exception as exc:
            raise _promote_gateway_call(
                exc, self.error_code, "Wiki 본문을 불러오지 못했습니다") from exc
        return len((row or {}).get("content") or "")
