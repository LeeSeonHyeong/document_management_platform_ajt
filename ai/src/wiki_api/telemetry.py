"""`complete` 호출 1건을 JSONL 한 줄로 남긴다.

계약 응답에는 토큰·비용 필드가 없고 바꿀 이유도 없다. 그런데 측정에는 그 값이 필요하고,
하네스는 서버를 HTTP 로 부르니 반환값을 볼 수 없다. `wiki_mcp/telemetry.py` 가 툴 호출에서
같은 문제를 이미 이렇게 풀었다 — 서버가 파일에 쓰고 하네스가 읽는다.

**이 로그로 업스트림 호출 횟수를 알 수는 없다.** 한 줄은 논리적 `complete` 1건이므로,
클라이언트가 내부에서 재시도하면 그것도 한 줄이다. 재시도 검증은 mock 서버가 받은 요청을
세는 쪽이다 (`tests/api/test_deepagents_complete.py`).
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path

from agent_runtime.base import CompletionResult

logger = logging.getLogger("llmwiki.api")

# 비어 있으면 기록하지 않는다. 배포에서 파일이 무한히 자라면 안 된다.
TELEMETRY_ENV = "AI_COMPLETION_LOG"


def record(path: str, request_id: str, tier: str,
           result: CompletionResult | None = None, *, error: str = "") -> None:
    """호출 1건을 한 줄로. `result` 가 없으면 실패한 호출이다.

    **실패도 기록한다.** 실패한 호출도 입력 토큰만큼 과금되는데, 성공만 기록하면 예산
    대조에서 그만큼이 사라져 "왜 크레딧이 더 나갔나"를 설명할 수 없다. 토큰 수는 알 수
    없으므로(응답을 못 받았다) 0 으로 두고 `error` 로 구분한다 — 그래서 **합계가 아니라
    건수**가 그 줄의 값이다.
    """
    target = os.environ.get(TELEMETRY_ENV, "")
    if not target:
        return
    row = {
        "requestId": request_id,
        "path": path,
        "tier": tier,
        "model": result.model if result else "",
        "inputTokens": result.input_tokens if result else 0,
        "outputTokens": result.output_tokens if result else 0,
        "cacheReadTokens": result.cache_read_tokens if result else 0,
        "cacheCreationTokens": result.cache_creation_tokens if result else 0,
        "elapsedSeconds": result.elapsed_seconds if result else 0.0,
    }
    if error:
        row["error"] = error[:200]
    try:
        with Path(target).open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    except OSError as exc:
        # 관측이 요청을 죽이면 안 된다. 로그 경로가 잘못된 것이 500 의 이유가 될 수 없다.
        logger.warning("관측 로그를 쓰지 못했다 (%s): %s", target, exc)
