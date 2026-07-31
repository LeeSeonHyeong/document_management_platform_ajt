"""실기동 확인 — 챗봇 에이전트에게 질문 **하나**를 보낸다.

**예산이 3달러다.** 정확도도 응답 시간 분포도 재지 않는다. 확인할 것은 다섯 가지뿐이다:

  * 200 이 온다
  * `sources` 가 비어 있지 않고 각 항목에 `title` 이 있다 (`answer_source.source_title`
    은 NOT NULL)
  * `questionType` 이 **질문**과 맞는다 — 읽은 자료의 종류가 아니다 (FR-QNA-002)
  * 가짜 창구에 조회가 실제로 도달했다
  * 일정 질문에서 **기간이 오늘 기준으로** 잡혔다 — 지침의 오늘 날짜가 먹는지 보는 것이다

쓰는 것: 가짜 조회 창구(`query_gateway.py`)를 이 프로세스에서 띄우고, 이미 떠 있는 AI
서버(`--api`)에 요청 1건을 보낸다. **AI 서버는 직접 띄운다** — 모델 키·런타임을 이 스크립트가
정하면 무엇으로 돌았는지 모르게 된다.

```bash
INTERNAL_API_KEY=k AI_RUNTIME=deepagents AI_MODEL=openai:gpt-4o-mini \\
  AI_BACKEND_BASE_URL=http://127.0.0.1:8090 \\
  uv run python -m wiki_api.serve --port 8000 &
uv run python experiments/chat_agent_sim.py --api http://127.0.0.1:8000 --key k
```
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
import time
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent))

from query_gateway import build_gateway  # noqa: E402

HERE = Path(__file__).resolve().parent
DEFAULT_CORPUS = HERE / "2026-07-27-opus46-12docs" / "data"
DEFAULT_PLAN = HERE / "chat_questions.json"

SCOPE = "ALL"
CAPABILITY = "cap-local"


class _Watched:
    """창구에 어떤 조회가 도달했는지 기록하는 래퍼. **조회 도달 확인의 근거다.**"""

    def __init__(self, app):
        self.app = app
        self.paths: list[str] = []

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            query = scope.get("query_string", b"").decode()
            self.paths.append(scope["path"] + (f"?{query}" if query else ""))
        await self.app(scope, receive, send)


def _serve(app, port: int) -> threading.Thread:
    import uvicorn

    config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning")
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    return thread


def _index_markdown(corpus: Path) -> str:
    return (corpus / "wiki" / SCOPE / "index.md").read_text(encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", required=True, help="이미 떠 있는 AI 서버 주소")
    parser.add_argument("--key", required=True, help="INTERNAL_API_KEY")
    parser.add_argument("--gateway-port", type=int, default=8090)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--plan", type=Path, default=DEFAULT_PLAN)
    parser.add_argument("--question", default="다음 전사 일정과 연차 규정을 알려줘.")
    parser.add_argument("--expect-type", default="mixed",
                        choices=["wiki", "schedule", "mixed"])
    args = parser.parse_args(argv)

    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    gateway = build_gateway(args.corpus, scope_key=SCOPE, capability=CAPABILITY,
                            api_key=args.key, schedules=plan["schedules"])
    watched = _Watched(gateway)
    _serve(watched, args.gateway_port)

    body = {
        "questionId": "500",
        "conversationId": "chat-sim-1",
        "question": args.question,
        "conversationMessages": [],
        "wikiIndexes": [{"scopeKey": SCOPE,
                         "indexMarkdown": _index_markdown(args.corpus),
                         "wikiCapability": CAPABILITY}],
    }
    # 에이전트 상한이 25초다. 그것보다 넉넉히 기다린다 — 여기서 끊으면 서버가 무엇을
    # 냈는지 못 본다.
    started = time.monotonic()
    response = httpx.post(f"{args.api.rstrip('/')}/internal/v1/answers", json=body,
                          headers={"X-Internal-API-Key": args.key}, timeout=90)
    elapsed = time.monotonic() - started

    # 상한(25초)을 조정할 근거가 이 숫자다. 한 번 잰 값이므로 분포가 아니다.
    print(f"상태 {response.status_code} · {elapsed:.1f}초")
    print(json.dumps(response.json(), ensure_ascii=False, indent=2)[:2000])
    print("\n창구에 도달한 조회:")
    for path in watched.paths:
        print(f"  {path}")

    if response.status_code != 200:
        print("\n실패 — 위 본문의 code 를 본다.")
        return 1

    payload = response.json()
    problems = []
    if not payload.get("sources"):
        problems.append("sources 가 비었다")
    for source in payload.get("sources") or []:
        if not source.get("title"):
            problems.append(f"title 이 없는 출처: {source}")
    if payload.get("questionType") != args.expect_type:
        problems.append(f"questionType 이 {payload.get('questionType')} 다 "
                        f"(질문 기준 기대값 {args.expect_type})")
    if not any(p.startswith("/internal/v1/wikis") or "/schedules" in p
               for p in watched.paths):
        problems.append("본문·일정 조회가 창구에 도달하지 않았다")

    print("\n" + ("\n".join(f"⚠ {p}" for p in problems) if problems
                  else "확인 항목 전부 통과"))
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
