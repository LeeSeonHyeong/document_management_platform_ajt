"""런타임에 직접 붙이는 도구 1개의 표현.

**이 타입이 `agent_runtime` 과 `wiki_api` 사이의 유일한 접점이다.** 타입을 아래쪽
(`agent_runtime`)에 두는 이유는 의존 방향이 `wiki_api → agent_runtime → wiki_mcp` 단방향
이어서다 — 런타임이 `wiki_api` 를 임포트하면 그 방향이 깨진다.

LangChain 을 여기서 임포트하지 않는다. 도구를 LangChain 형태로 감싸는 것은
`deep_agents.py` 안에서만 일어난다 — 배포 의존성이 없는 설치에서도 이 모듈이 임포트돼야
한다.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True)
class AgentTool:
    """`input_schema` 는 JSON Schema 의 object 하나다. `call` 은 문자열을 돌려준다 —
    모델이 읽을 것이므로 사람이 읽을 수 있는 형태여야 한다."""

    name: str
    description: str
    input_schema: dict
    call: Callable[..., str]
