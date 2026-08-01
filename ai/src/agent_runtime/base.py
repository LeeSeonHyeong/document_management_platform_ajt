"""The runtime seam — what drives the MCP tools.

Two implementations, one interface. The MCP server does not know which one is
calling, which is the whole reason the swap is cheap: the Claude Code CLI bills
against a subscription and runs today, and DeepAgents bills against an API key
and takes over when there is budget.

The boundary API convention 10.2 draws sits here: a runtime receives an
instruction and returns a transcript. It never touches the DB or the filesystem
directly — every effect goes through an MCP tool call, and the MCP server is the
component that owns permission, transaction, and storage.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol


@dataclass
class RunResult:
    """One ingest run.

    `tool_calls` is what distinguishes "never looked at a page" from "looked and
    still duplicated it" — the single most useful diagnostic when output quality
    disappoints, and the earlier spike could not tell the two apart.
    """

    text: str
    tool_calls: dict[str, int] = field(default_factory=dict)
    input_tokens: int = 0
    output_tokens: int = 0
    # Reported by the CLI. `cost_usd` is the only cost figure that means anything
    # across runtimes — token counts are inflated by each harness differently.
    turns: int = 0
    cost_usd: float = 0.0
    elapsed_seconds: float = 0.0
    error: str | None = None
    # 턴별·툴별 내역. `agent_runtime/stream_json.py` 의 `StreamSummary.as_dict()` 모양이고,
    # 그것을 내지 못하는 런타임은 `None` 이다. **계약 응답에는 실리지 않는다** — 측정
    # (`report.json`)과 진단용이다.
    #
    # 총계만으로는 D8 을 좁힐 수 없어서 넣었다: 시간 ≈ 출력토큰 ÷ 55 로 거의 일정하므로
    # 출력 토큰이 곧 지연시간이고, 그것을 줄이려면 어느 턴에서 나오는지부터 알아야 한다.
    detail: dict | None = None
    # 모델이 낸 구조화 응답 (`response_format` 을 준 실행에서만 채워진다). 챗봇의 「쓴 자료
    # 신고」와 질문 유형이 여기 온다. **`detail` 에 섞지 않는다** — 그쪽은 측정·진단용이고
    # 계약 응답에 실리지 않는 값이다.
    structured: dict | None = None


@dataclass(frozen=True)
class CompletionResult:
    """MCP 없는 단발 호출 1건.

    `RunResult` 와 나눠 둔 이유는 그쪽이 `tool_calls`·`turns` 처럼 에이전트에만 있는 필드를
    들고 있어서다 — 단발 호출에서 그것들은 언제나 빈 값이고, 같은 형을 쓰면 리포트의
    "툴을 0번 불렀다"가 의미 있는 관측인지 애초에 툴이 없었던 것인지 구분되지 않는다.

    `model` 은 **응답이 말한 모델**이다. 요청한 이름이 아니다. 게이트웨이(SSAFY GMS)가
    모델을 바꿔 끼울 수 있어서, 요청값을 기록하면 무엇을 쟀는지 모르게 된다.

    `cache_read_tokens` 가 여기 있는 것은 예산 때문이다. 목차처럼 여러 호출이 공유하는
    접두사에 캐시를 걸면 읽기가 10% 과금인데, 접두사 순서를 잘못 짜면 적중이 0 이 되고
    그것이 조용히 일어난다. 이 필드를 보면 알 수 있다.
    """

    text: str
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_tokens: int = 0
    cache_creation_tokens: int = 0
    model: str = ""
    elapsed_seconds: float = 0.0


# tier → 실제 모델 이름은 런타임이 번역한다. 호출자가 모델 문자열을 직접 주면 런타임마다
# 이름 체계가 달라 깨진다 (`anthropic:claude-…` vs CLI 표기).
FAST = "fast"
QUALITY = "quality"

# 단발 호출의 기본 상한. 에이전트 루프(최대 30분)와 같은 예산을 줄 이유가 없다 — 여기서
# 오래 걸리는 것은 진행이 아니라 고장이다. 호출자가 단계별로 더 짧게 준다.
DEFAULT_COMPLETE_TIMEOUT = 120


class Runtime(Protocol):
    """An agent runtime wired to one MCP server."""

    name: str

    def run(self, instruction: str, *, root: Path, scope_key: str,
            job_id: str, timeout: int | None = None) -> RunResult: ...
    # `timeout` 은 이 문서에 허용된 초다 (`agent_runtime/limits.py`). 세션은 sync 런타임을
    # `asyncio.to_thread` 로 띄우는데 그 스레드는 취소할 수 없으므로, 런타임 자체 timeout
    # 이 NFR-PERF-002 를 강제하는 유일한 수단이다. 안 넘기면 각 런타임의 기존 기본값.

    def run_with_tools(self, guide: str, question: str, *, tools: list,
                       max_turns: int, timeout: int,
                       response_format=None) -> RunResult: ...
    # MCP 없이 도구를 직접 붙여 도는 실행. **쓰기가 없는 작업(챗봇)만 쓴다** — 작업 공간도
    # 스코프 격리도 없다. `tools` 는 `agent_runtime.tools.AgentTool` 목록이다.
    # `guide` 는 시스템 지침, `question` 은 첫 사용자 메시지다. **한 문자열을 양쪽에 넣지
    # 않는다** — 목차가 두 번 실려 입력이 두 배가 된다.
    # 상한은 턴 수(`max_turns`)가 주 장치이고 `timeout` 은 한 턴이 이상하게 오래 걸릴 때
    # 끊는 안전장치다. 턴 상한에 걸린 실행은 `RunResult.error == "turn_limit"` 이다 —
    # 호출자가 그것으로 `AGENT_TURN_LIMIT_REACHED` 를 낸다.

    def complete(self, messages: list[dict], *, tier: str = QUALITY,
                 timeout: int | None = None) -> CompletionResult: ...
    # ----- MCP 없는 단발 호출 -------------------------------------------------
    #
    # 문맥 선택과 챗봇 답변은 **에이전트가 아니다** — 입력이 목차·본문·질문뿐이라 툴이
    # 필요 없고, MCP 서버를 띄우는 것은 낭비이자 사고 위험이다(선택 호출이 위키를 고칠 수
    # 있게 된다). `bare=True` 플래그를 `run()` 에 더하는 대신 별도 이름으로 둔 이유는
    # 두 가지가 서로 다른 결과형을 내기 때문이다 (`RunResult` vs `CompletionResult`).
    #
    # `messages` 는 `{"role": ..., "content": ...}` 목록이다. `content` 는 문자열이거나
    # content block 목록이다 — 후자는 캐싱(`cache_control`)에 필요하다. CLI 런타임은
    # block 을 텍스트로 이어붙이고 `cache_control` 을 버린다.
    #
    # **`timeout` 은 실제로 강제해야 한다.** sync 구현을 `asyncio.to_thread` 로 띄우면
    # 그 스레드를 취소할 수 없으므로, `run` 과 같은 이유로 런타임 자체 상한이 유일한
    # 수단이다. 호출자 쪽 `asyncio.wait_for` 는 호출자를 풀어주기만 한다.
    #
    # **재시도를 켜지 않는다.** 실패 1건이 조용히 2~3배 청구된다.


def runs_tools_in_process(runtime) -> bool:
    """이 런타임이 도구를 이 프로세스 안에서 도는가.

    창구 모드(`FederatedVaultFS`)가 성립하는 조건이다. 별도 프로세스의 MCP 서버는
    `fs_factory`(`wiki_mcp/local_server.py`)가 `LocalVaultFS` 라 본문이 빈 페이지를
    에이전트에게 보이고, 에이전트는 "내용이 없다" 고 판단해 라이브를 덮는다. 범위 변경
    중단 신호도 프로세스 밖으로 나올 길이 없다.

    판정을 `arun` 유무로 한다. 예전에는 `spawns_mcp_server` 불리언 마커였는데, 마커는
    **붙이는 것을 잊을 수 있고** 그 기본값 하나에 데이터 손실이 걸려 있었다. `arun` 은
    시그니처가 `fs`(살아 있는 파이썬 객체)를 받는다 — 하위 프로세스로는 애초에 받을 수
    없는 인자라 **구조가 보증한다.** 마커를 지운 이유가 이것이다 (S15P11B106-152).

    판정하는 곳은 `wiki_api/session.py` `_assert_runtime_can_use_the_gateway` 한 곳이다.
    """
    return callable(getattr(runtime, "arun", None))


def ingest_instruction(document_address: str, scope_key: str) -> str:
    """The single unit of work: one source document becomes wiki pages.

    This is deliberately short. The workflow lives in the `guide` tool, so both
    runtimes issue the same instruction and neither carries a private copy of the
    standards that would drift from the other.
    """
    return (
        f"`guide` 도구를 먼저 불러 작업 방식을 확인한다.\n\n"
        f"그다음 범위 `{scope_key}`에서 원본문서 `{document_address}` 1건을 위키로 반영한다. "
        f"guide의 「원본문서 1건을 위키로 만들기」 순서를 그대로 따른다 — 문서를 읽고, "
        f"겹치는 페이지를 검색해 본문까지 읽고, 기존 페이지를 고치거나 새로 만들고, "
        f"`index.md`를 갱신하고, 마지막에 `lint`를 부른다.\n\n"
        f"`lint`가 `error`를 내면 고치고 다시 부른다. error가 0이 될 때까지 끝내지 않는다."
    )


def selection_instruction(parsed_markdown: str, current_index: str,
                          change_type: str = "document_added",
                          removed_markdown: str | None = None) -> str:
    """1단계 문맥 선택의 프롬프트 (설계 §5). **툴이 없는 단발 호출이다.**

    에이전트 지시문(`ingest_instruction` 등)과 달리 `guide` 를 부르라고 하지 않는다 —
    MCP 서버가 없기 때문이다. 입력은 목차와 문서 본문뿐이고, 출력은 목차 링크에 실재하는
    `wikiId` 최대 5개다. 파싱은 `api/selection.py` 가 하며 **목차에 없는 ID 는 버린다** —
    모델이 ID 를 지어내도 그 결과가 2단계 변환의 하이드레이션으로 흘러가지 않게 한다.

    `document_removed` 는 질문이 뒤집힌다: 「이 문서를 반영하려면 어디를 읽어야 하나」가
    아니라 「이 문서를 **근거로 쓴** 위키가 어디인가」다. 목차에는 요약만 있고 각주 관계가
    없으므로 이 선택의 정확도는 낮을 수 있다 (설계 §7 의 리스크 — Spring 이
    `document_wiki_refs` 로 인용 위키를 직접 보내는 편이 옳다는 제안이 회신에 있다).
    """
    body = (removed_markdown or "") if change_type == "document_removed" else parsed_markdown
    if change_type == "document_removed":
        question = ("아래 원본문서가 **삭제**됐다. 이 문서를 근거(각주)로 썼을 가능성이 "
                    "높은 위키를 고른다.")
    elif change_type == "document_replaced":
        question = ("아래 원본문서가 **교체**됐다. 옛 내용을 근거로 썼거나 새 내용과 겹치는 "
                    "위키를 고른다.")
    else:
        question = ("아래 원본문서를 위키에 반영하려 한다. 그 작업에 **읽어야 할** 위키를 "
                    "고른다 — 내용이 겹쳐 고쳐야 하거나, 병합 후보이거나, 링크할 곳이다.")

    return (
        f"{question}\n\n"
        f"관련도 순서로 **최대 5개**, 목차 링크(`pages/{{wikiId}}.md`)에 실재하는 wikiId 로만 "
        f"답한다. 관련된 위키가 없으면 빈 배열을 낸다 — 억지로 채우지 않는다.\n\n"
        f"다른 말 없이 JSON 만 출력한다:\n"
        f'{{"wikiIds": ["101", "108"], "reason": "고른 이유 한두 문장"}}\n\n'
        f"## 현재 목차\n\n{current_index}\n\n"
        f"## 원본문서\n\n{body}\n"
    )


def reconcile_instruction(document_address: str, scope_key: str, change_type: str,
                          affected: list[dict]) -> str:
    """원본문서가 사라졌거나 교체됐을 때 위키를 맞추는 지시.

    고칠 곳을 지시문에 적어준다. 참조 그래프가 각주 단위로 알고 있으니 에이전트가 위키를
    전수 탐색할 이유가 없다 — 그게 이 작업이 추가 변환보다 빨라야 하는 이유다.

    `change_type` 은 v1.1.0 요청의 `changeType` 값을 그대로 받는다 — 옛 `reason` 인자와
    문자열이 같아서(`document_removed`·`document_replaced`) 이름만 바뀌었다 (설계 §1).
    """
    lines = [
        f"  - `{item['address']}` 각주 [^{item.get('footnote')}] "
        f"— {item.get('location') or '위치 미기재'} — \"{(item.get('quote') or '')[:60]}\""
        for item in affected
    ]
    listing = "\n".join(lines) or "  (인용한 페이지가 없다)"

    if change_type == "document_replaced":
        # 새 내용은 같은 주소에 이미 얹혀 있다 (`api/routers/wiki.py::_restage_replacement`).
        # 그 사실을 적지 않으면 에이전트가 옛 내용만 있다고 보고 문단을 지우는 쪽으로 간다.
        what = (f"원본문서 `{document_address}`가 **교체**됐다. **그 주소를 `read` 하면 "
                f"이미 새 내용이 들어 있다** — 옛 내용은 남아 있지 않다. 새 내용을 읽고 옛 "
                f"내용을 근거로 쓴 문단을 새 내용에 맞게 고친다. 각주의 인용문도 새 내용에서 "
                f"그대로 옮겨 적는다. 새 내용에 없는 주장은 지운다.")
    else:
        what = (f"원본문서 `{document_address}`가 **삭제**됐다. 이 문서를 근거로 쓴 문단과 "
                f"각주를 걷어낸다. 근거가 전부 사라진 페이지는 `delete` 한다.")

    return (
        f"`guide` 도구를 먼저 불러 작업 방식을 확인한다.\n\n"
        f"범위 `{scope_key}`에서 {what}\n\n"
        f"고칠 곳은 다음과 같다:\n{listing}\n\n"
        f"다른 문서를 근거로 한 문단은 건드리지 않는다. 문단을 지우면 남은 각주 번호가 "
        f"어긋나지 않게 정리한다. `index.md`에서 사라진 페이지 줄을 빼고, 마지막에 `lint`를 "
        f"부른다.\n\n"
        f"`lint`가 `error`를 내면 고치고 다시 부른다. error가 0이 될 때까지 끝내지 않는다."
    )


def edit_instruction(wiki_address: str, scope_key: str, instruction: str,
                     chat_history: list[dict]) -> str:
    """관리자의 자연어 수정 지시 (FR-AI-005·006).

    FR-AI-006 이 "요청과 무관한 Wiki 는 변경하지 않는다"를 요구하므로 그 문장을 지시에
    넣는다. 근거 없는 변경 금지(NFR-AI-002)도 여기서 다시 못 박는다 — 채팅은 사람이
    시키는 것이라 근거를 건너뛰기 쉽다.
    """
    history = "\n".join(
        f"  {'관리자' if m.get('senderType') == 'admin' else '에이전트'}: {m.get('content')}"
        for m in chat_history
    )
    context = f"\n\n지금까지의 대화:\n{history}" if history else ""

    return (
        f"`guide` 도구를 먼저 불러 작업 방식을 확인한다.\n\n"
        f"범위 `{scope_key}`의 페이지 `{wiki_address}`에 대해 관리자가 다음을 요청했다:\n\n"
        f"  {instruction}{context}\n\n"
        f"페이지를 읽고 요청대로 고친다. **요청과 무관한 페이지는 변경하지 않는다.** "
        f"원본문서에서 근거를 찾을 수 없는 내용은 새로 쓰지 않는다 — 근거가 없으면 그렇다고 "
        f"답한다. 각주를 유지하고, 본문이 바뀌면 `index.md`의 요약도 맞춘다.\n\n"
        f"마지막에 `lint`를 부른다. `error`가 나면 고치고 다시 부른다."
    )


# 챗봇 프롬프트 두 개(`answer_context_instruction`·`answer_instruction`)는 계약 1.8.0 에서
# 지웠다. 챗봇은 이제 지침을 시스템 자리에 싣고 도구로 조회하는 에이전트 하나이고, 그 지침은
# `wiki_api/answer_guide.py` 가 정본이다. **프롬프트 캐싱은 다시 재야 한다** — 위 두 함수의
# `cache_control` 블록 배치는 Anthropic 경로에서 측정한 것이고 지금 제공자는 OpenAI 다.

def render_messages(messages: list[dict], *, label_single: bool = False,
                    trailing_newline: bool = False) -> str:
    """messages 목록 → 문자열. 문자열밖에 못 받는 경로가 쓴다 (CLI, `run` fallback).

    **`cache_control` 을 버린다.** 문자열로 내려가는 순간 캐싱이 성립하지 않는다 — 캐싱은
    배포 경로(messages 목록을 그대로 받는 쪽)의 예산 문제다.

    두 인자가 있는 이유는 호출자의 의미론이 다르기 때문이다.

      * `label_single` — 메시지가 하나뿐일 때 "사용자: " 를 붙일지. `run` fallback 은
        기존 프롬프트를 그대로 넘겨야 해서 붙이지 않고, CLI 는 붙인다
      * `trailing_newline` — CLI 는 붙인다

    역할 표기는 `edit_instruction` 과 같은 어휘를 쓴다 — 사용자/답변.
    """
    label = {"user": "사용자", "assistant": "답변", "system": "지시"}
    single = len(messages) == 1
    lines = []
    for message in messages:
        who = label.get(str(message.get("role")), "사용자")
        content = message.get("content")
        if isinstance(content, str):
            body = content
        else:
            body = "\n".join(
                block.get("text", "") if isinstance(block, dict) else str(block)
                for block in content or []
            )
        lines.append(body if single and not label_single else f"{who}: {body}")
    return "\n\n".join(lines) + ("\n" if trailing_newline else "")
