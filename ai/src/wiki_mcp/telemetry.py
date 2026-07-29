"""Tool-call counting, done in the server rather than the client.

Why here: `claude -p --output-format json` does not report which tools were
called, and DeepAgents reports them in a different shape again. Counting on the
server side is runtime-agnostic and counts what actually arrived, not what a
client claims it sent.

Why it matters: when a page comes out duplicated or thin, "never called `search`"
and "called `search`, read nothing, wrote anyway" need different fixes, and only
the counts tell them apart. The earlier spike could not distinguish the two.

Off unless the server is started with `--tool-log PATH`. One line per call,
appended, so a crashed run still leaves a partial record.

**Not an environment variable.** Claude Code's `mcpServers.env` *replaces* the
child environment rather than extending it, so setting one variable there drops
`PATH` and the `uv` launcher is never found — the server dies and the agent sees
no tools at all, while the run still reports success. Cost one silent ingest that
wrote nothing. A command-line argument has no such failure mode.

## 검색어 수집 (`--query-log`) — 별개 스위치다

설계 문서 §2.3 이 「위키 변환 질의는 정확어 성격이다」를 전제로 2글자 분해를 골랐다. 정확어
R@5 0.95 대 자연어 0.25 이므로 그 전제가 틀리면 결론이 흔들린다. 그런데 그것은 **가정이다** —
실제 검색어는 원본문서 문장이 아니라 에이전트가 생성한다. 횟수만으로는 재검증할 수 없다.

두 가지 이유로 `--tool-log` 와 나눴다.

  * **개인정보.** 질의에 사내 내용이 실린다. 기본은 꺼져 있고 측정 세션에서만 켠다.
    횟수는 진단에 늘 필요하지만 질의는 측정할 때만 필요하다.
  * **`read_counts` 가 공백으로 나눠 센다** (`Counter(text.split())`). 같은 파일에 질의
    문자열을 섞으면 어절마다 툴 이름으로 세어져 `guards.bypassed_server` 판정이 망가진다.
"""

from __future__ import annotations

import json
import re
import unicodedata
from collections import Counter
from pathlib import Path


def count_tool_calls(mcp, log_path: str | None) -> None:
    """Count every tool call by wrapping the dispatcher, not the tools.

    Wrapping the tool functions themselves does not work. FastMCP builds each
    tool's argument model from its annotations, and the modules use
    `from __future__ import annotations`, so those annotations are strings
    resolved against the *defining function's* globals. A wrapper defined here
    resolves them against this module instead, where names like `Context` and
    `Scope` do not exist. Every tool then fails to register with
    "`lintArguments` is not fully defined" — and the server still starts, serving
    zero tools, so the agent reports success having done nothing. That is how
    this was found, and it cost a silent ingest.

    `ToolManager.call_tool` is below all of that: one function, a stable
    signature, and every call goes through it.
    """
    if not log_path:
        return

    manager = mcp._tool_manager
    original = manager.call_tool

    async def counted(name, arguments, context=None, convert_result=False):
        _record(log_path, name)
        return await original(name, arguments, context=context,
                              convert_result=convert_result)

    manager.call_tool = counted


def _record(log_path: str, name: str) -> None:
    try:
        with open(log_path, "a", encoding="utf-8") as fh:
            fh.write(name + "\n")
    except OSError:
        # Telemetry must never take down a run.
        pass


def read_counts(log_path: str | Path) -> dict[str, int]:
    path = Path(log_path)
    if not path.exists():
        return {}
    return dict(Counter(path.read_text(encoding="utf-8").split()))


# ----- 검색어 수집 -----------------------------------------------------------
#
# 프로세스 전역이다. `vaultfs/local.py` 의 연결과 같은 이유로 그렇게 둔다 — 프로세스 1개 =
# 스코프 1개 = 작업 1개이므로 (`local_server.py`) 세션마다 다른 sink 가 필요할 일이 없다.
_query_log: Path | None = None


def enable_query_log(path: str | Path) -> None:
    """검색어 기록을 켠다. **`local_server.py --query-log` 를 준 세션에서만 불린다.**"""
    global _query_log
    _query_log = Path(path)


def disable_query_log() -> None:
    global _query_log
    _query_log = None


def record_search(query: str, hits: int, *, scope_key: str) -> None:
    """검색 1건. `tools/search.py` 가 결과를 만든 뒤 부른다 — 히트 수를 알아야 하기 때문이다.

    0건 질의가 가장 중요한 신호다. 그것이 현행 구현이 무엇을 못 찾았는지 보여 준다.
    """
    if _query_log is None:
        return
    row = {"query": query, "hits": int(hits), "scopeKey": scope_key}
    try:
        with open(_query_log, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    except OSError:
        # 계측이 실행을 죽이면 안 된다 (`_record` 와 같은 이유).
        pass


def read_queries(log_path: str | Path) -> list[dict]:
    """줄 단위 JSON 을 읽는다. **잘린 줄은 건너뛴다** — 죽은 실행의 로그는 마지막 줄이
    잘려 있고, 그것 때문에 전체를 못 읽으면 진단 자료가 사라진다."""
    path = Path(log_path)
    if not path.exists():
        return []
    rows: list[dict] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(row, dict):
            rows.append(row)
    return rows


_WORD_RE = re.compile(r"[0-9A-Za-z가-힣]+")


def classify_query(query: str, source_text: str) -> str | None:
    """이 질의가 정확어인가 자연어인가. 분류 못 하면 `None`.

    기준은 원본문서에 **글자 그대로** 있는지다. 어절 단위로 비교하지 않는 이유는 조사다 —
    원본문서에는 `"연차를"` 로 있고 질의는 `"연차"` 다. 어절이 같아야 한다고 보면 그것을
    자연어로 잘못 센다.

    어절이 **전부** 있어야 정확어다. 일부만 있으면 자연어로 센다 — 정확어 검색의 강점은
    질의어가 그대로 맞는 것이고, 하나라도 빠지면 그 강점이 사라진다.
    """
    words = _WORD_RE.findall(unicodedata.normalize("NFC", query))
    if not words:
        return None
    haystack = unicodedata.normalize("NFC", source_text).lower()
    return "exact" if all(w.lower() in haystack for w in words) else "para"


def summarise_queries(log_path: str | Path, source_text: str) -> dict:
    """설계 문서 §2.3 재검증의 산출물.

    「위키 변환 질의는 정확어 성격이다」가 맞으면 `exactRatio` 가 1 에 가깝다. 낮으면
    자연어 R@5 (0.25) 가 실제 품질을 지배한다는 뜻이고, 의미 검색 보류 판단을 다시 봐야
    한다 (설계 문서 §6.5).
    """
    rows = read_queries(log_path)
    kinds = [classify_query(str(row.get("query") or ""), source_text) for row in rows]
    exact = sum(1 for k in kinds if k == "exact")
    para = sum(1 for k in kinds if k == "para")
    classified = exact + para
    return {
        "total": len(rows),
        "exact": exact,
        "para": para,
        "exactRatio": round(exact / classified, 3) if classified else 0.0,
        "zeroHit": sum(1 for row in rows if not row.get("hits")),
    }
