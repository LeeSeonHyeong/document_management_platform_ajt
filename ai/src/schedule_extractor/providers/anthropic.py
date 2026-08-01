"""배포 어댑터 — Anthropic Messages API 를 `httpx` 로 직접 부른다.

SDK 를 새 의존성으로 넣지 않는다. 어댑터가 짧아 SDK 버전에 묶이지 않는 쪽이 싸고,
`httpx` 는 이미 의존성에 있다.

**구조화 출력은 tool use 로 강제한다.** 텍스트로 JSON 을 달라고 하면 앞뒤에 설명이
붙어 파싱이 흔들린다. `tool_choice` 로 그 툴을 반드시 부르게 하면 `input` 이 스키마를
지킨 dict 로 온다.

모델 이름은 정확한 이름을 쓴다 — `opus` 별칭은 시점에 따라 다른 모델로 해석돼 두
측정의 비교를 조용히 깨뜨린다 (`ai/CLAUDE.md` 함정).

`thinking` 을 보내지 않는다. Opus 4.6 은 이 필드를 빼면 **사고를 끈 채로 돈다** —
적응형이 기본이 아니라 미설정이 곧 꺼짐이다. 일정 추출은 문서를 그대로 옮기는 한 번의
구조화 출력이라 그것이 맞는 설정이다. 품질이 모자라 사고를 켜야 하면
`{"type": "adaptive"}` 를 명시하고, 그때 `max_tokens` 를 함께 올린다 — 상한은 사고와
응답을 합쳐서 센다.
"""

from __future__ import annotations

import httpx

from ..provider import ProviderError

_TOOL_NAME = "emit_schedules"
_API_VERSION = "2023-06-01"
_MAX_TOKENS = 8192


class AnthropicProvider:
    def __init__(self, *, model: str, api_key: str, base_url: str,
                 timeout_seconds: float,
                 transport: httpx.BaseTransport | None = None):
        self._model = model
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout_seconds
        self._transport = transport

    async def complete_json(self, prompt: str, schema: dict) -> dict:
        payload = {
            "model": self._model,
            "max_tokens": _MAX_TOKENS,
            "messages": [{"role": "user", "content": prompt}],
            "tools": [{"name": _TOOL_NAME,
                       "description": "추출한 일정을 구조화해 돌려준다.",
                       "input_schema": schema}],
            "tool_choice": {"type": "tool", "name": _TOOL_NAME},
        }
        headers = {"x-api-key": self._api_key, "anthropic-version": _API_VERSION,
                   "content-type": "application/json"}
        try:
            async with httpx.AsyncClient(timeout=self._timeout,
                                         transport=self._transport) as client:
                response = await client.post(f"{self._base_url}/v1/messages",
                                             json=payload, headers=headers)
                response.raise_for_status()
                body = response.json()
        except (httpx.HTTPError, ValueError) as error:
            # 예외 문자열에 요청 헤더가 실릴 수 있으므로 키를 담지 않게 직접 조립한다.
            raise ProviderError(
                f"Anthropic 호출 실패: {type(error).__name__}") from error

        # 안전 분류기가 거절하면 HTTP 200 인데 결과가 없다. `content` 를 그냥 훑으면
        # "구조화 출력을 내지 않았다" 로 뭉개져 원인이 사라진다.
        if body.get("stop_reason") == "refusal":
            category = (body.get("stop_details") or {}).get("category")
            raise ProviderError(f"모델이 요청을 거절했다: {category}")

        # 출력 상한에 걸리면 tool_use 의 `input` 이 잘린 채로 온다. 그래도 dict 이므로
        # 아래 반복문을 그냥 통과하고, 일정 일부가 사라진 결과가 정상 응답으로 나간다 —
        # 관리자는 문서에 있던 일정이 왜 없는지 알 방법이 없다. 잘린 결과는 결과가
        # 아니므로 여기서 실패로 바꾼다. 라우터가 500 으로 내보내면 관리자가 재시도한다.
        if body.get("stop_reason") == "max_tokens":
            raise ProviderError(f"모델 출력이 상한({_MAX_TOKENS} 토큰)에 걸려 잘렸다")

        for block in body.get("content") or ():
            if block.get("type") != "tool_use" or block.get("name") != _TOOL_NAME:
                continue
            arguments = block.get("input")
            if isinstance(arguments, dict):
                return arguments
        raise ProviderError("모델이 구조화 출력을 내지 않았다")
