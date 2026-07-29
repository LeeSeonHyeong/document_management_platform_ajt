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


class Runtime(Protocol):
    """An agent runtime wired to one MCP server."""

    name: str

    def run(self, instruction: str, *, root: Path, scope_key: str,
            job_id: str, timeout: int | None = None) -> RunResult: ...
    # `timeout` 은 이 문서에 허용된 초다 (`agent_runtime/limits.py`). 세션은 sync 런타임을
    # `asyncio.to_thread` 로 띄우는데 그 스레드는 취소할 수 없으므로, 런타임 자체 timeout
    # 이 NFR-PERF-002 를 강제하는 유일한 수단이다. 안 넘기면 각 런타임의 기존 기본값.

    # ----- 선택적: MCP 없는 단발 호출 (v1.1.0 §5) -----------------------------
    #
    #     def complete(self, prompt: str, *, timeout: int | None = None)
    #             -> RunResult | str: ...
    #
    # 1단계 문맥 선택(`api/selection.py`)은 **에이전트가 아니다** — 입력이 목차와 문서
    # 본문뿐이라 툴이 필요 없고, MCP 서버를 띄우는 것은 낭비이자 사고 위험이다(선택
    # 호출이 위키를 고칠 수 있게 된다). 그래서 "MCP 없이 한 번만 물어본다"는 능력을
    # 선택 메서드로 둔다 — `bare=True` 플래그를 `run()` 에 더하는 대신 별도 이름으로
    # 둔 이유는 프로토콜에 필수로 올리면 두 런타임이 즉시 계약 위반이 되기 때문이다.
    # (앞 판본은 "`claude_code.py` 가 수정 금지"라고 적었다. 그것은 v1.1.0 적응 설계의
    # 「불변」 목록이었고 그 이터레이션 한정이다 — S15P11B106-143 이 계획대로 그 파일과
    # `tools/lint.py`·`shared/schema.sql`·`vaultfs/local.py` 를 고쳤다.)
    #
    # **두 프로덕션 런타임 모두 아직 이것을 구현하지 않는다.** `api/selection.py` 는
    # 그때 `run`/`arun` 으로 물러서서 빈 임시 루트를 준다 — 동작하지만 MCP 서버가
    # 뜬다(툴은 빈 범위를 보므로 무해하다). claude-code 로 선택을 프로덕션에서 돌리려면
    # `complete()` 를 갖는 no-MCP 변형이 필요하다. 미해결로 남긴다.


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
