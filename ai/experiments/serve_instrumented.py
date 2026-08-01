"""계측을 끼워 AI 서버를 띄운다 — 측정 전용. `src/` 는 한 줄도 고치지 않는다.

**왜 필요한가.** 계약 응답에는 토큰·툴 호출 수·조회 API 호출 수가 없고, 있어서도 안 된다
(`api/schemas.py`). 그런데 조회 API 경로를 옛 기준선과 대조하려면 그 값들이 있어야 한다 —
기준선(`backend_sim.py --transport in-process`)은 런타임을 직접 불러 `report.json` 에
남겼지만, 조회 API 경로는 HTTP 로 서버를 부르므로 하네스가 반환값을 볼 수 없다.
`wiki_api/telemetry.py` 가 단발 `complete` 호출에서 같은 문제를 이미 「서버가 파일에
쓰고 하네스가 읽는다」로 풀었다. 여기서는 그 파일에 남지 않는 두 지점을 감싼다.

  * `DeepAgentsRuntime.arun` — 에이전트 실행 1회의 툴 호출·토큰·턴·소요 시간
  * `WikiQueryClient.aclose` — 그 요청이 쓴 조회 API 호출 수와 조회 예산

`AI_MEASURE_LOG` 에 JSONL 로 한 줄씩 붙인다. 환경변수가 비면 아무것도 쓰지 않는다.
프로덕션 코드는 이 파일을 임포트하지 않으며, 백엔드가 진짜 조회 API 를 만들면
`query_gateway.py` 와 함께 사라진다.

```bash
AI_MEASURE_LOG=/tmp/measure.jsonl BACKEND_BASE_URL=http://127.0.0.1:8901 \
  uv run python -m experiments.serve_instrumented --port 8010
```
"""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

MEASURE_ENV = "AI_MEASURE_LOG"


def _append(row: dict) -> None:
    """관측이 요청을 죽이면 안 된다 — 쓰기 실패는 삼킨다 (`wiki_api/telemetry.py` 와 같다).

    `OSError` 뿐 아니라 `TypeError` 도 삼긴다 — `row` 에 `json.dumps` 가 직렬화하지
    못하는 값(예: 알 수 없는 객체)이 섞여 들어와도 계측 때문에 서버가 죽으면 안 된다.
    """
    target = os.environ.get(MEASURE_ENV, "")
    if not target:
        return
    try:
        with Path(target).open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    except (OSError, TypeError) as exc:
        print(f"경고: 측정 로그를 쓰지 못했다 ({target}): {exc}", file=sys.stderr)


def install() -> None:
    """감싸기를 건다. **서버를 만들기 전에 부른다** — `build_app` 이 런타임 인스턴스를
    하나 만들어 들고 있으므로, 클래스가 아니라 인스턴스를 감싸려 하면 늦는다.
    """
    from agent_runtime.deep_agents import DeepAgentsRuntime
    from wiki_mcp.vaultfs.query_client import WikiQueryClient

    original_arun = DeepAgentsRuntime.arun

    async def arun(self, instruction, **kwargs):
        started = time.monotonic()
        result = await original_arun(self, instruction, **kwargs)
        _append({
            "kind": "agent",
            "model": getattr(self, "model", ""),
            "toolCalls": dict(getattr(result, "tool_calls", {}) or {}),
            "inputTokens": getattr(result, "input_tokens", 0),
            "outputTokens": getattr(result, "output_tokens", 0),
            "turns": getattr(result, "turns", 0),
            "elapsedSeconds": (getattr(result, "elapsed_seconds", None)
                               or round(time.monotonic() - started, 1)),
            "error": getattr(result, "error", None),
        })
        return result

    DeepAgentsRuntime.arun = arun

    original_aclose = WikiQueryClient.aclose

    async def aclose(self):
        # 요청 1건에 클라이언트 1개다 (`wiki_api/session.py`) — 세션이 끝날 때 닫으므로
        # 이 줄이 곧 그 요청의 조회 API 소비량이다. 허가값은 절대 싣지 않는다 (계약 1.6.0).
        _append({
            "kind": "gateway",
            "queryCalls": getattr(self, "calls", 0),
            "callBudget": getattr(self, "call_budget", 0),
            "catalogPages": getattr(self, "catalog_pages", 0),
            "bodyFetches": getattr(self, "body_fetches", 0),
        })
        return await original_aclose(self)

    WikiQueryClient.aclose = aclose


def main() -> None:
    install()
    from wiki_api.serve import main as serve_main

    target = os.environ.get(MEASURE_ENV, "")
    print(f"계측 켜짐 — {target or '(AI_MEASURE_LOG 이 비어 있어 기록하지 않는다)'}",
          file=sys.stderr)
    serve_main()


if __name__ == "__main__":
    main()
