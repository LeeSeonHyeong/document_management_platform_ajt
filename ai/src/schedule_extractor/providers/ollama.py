"""로컬 모델 어댑터 — 개발 확인과 품질 측정에 쓴다. 과금이 없다.

ollama 의 `format` 에 JSON Schema 를 그대로 넘기면 문법 제약으로 출력을 강제한다.
`temperature=0` 은 재현성을 위한 것이지 보장은 아니다 — 그래서 이 경로를 쓰는
테스트는 `@pytest.mark.llm` 이다 (설계 §6.4).

배포 어댑터는 `anthropic.py` 다. 둘의 유일한 접점은 `complete_json` 이다.
"""

from __future__ import annotations

import json

import httpx

from ..provider import ProviderError


class OllamaProvider:
    def __init__(self, *, model: str, base_url: str, timeout_seconds: float,
                 transport: httpx.BaseTransport | None = None):
        self._model = model
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout_seconds
        self._transport = transport

    async def complete_json(self, prompt: str, schema: dict) -> dict:
        payload = {"model": self._model, "prompt": prompt, "format": schema,
                   "stream": False, "options": {"temperature": 0}}
        try:
            async with httpx.AsyncClient(timeout=self._timeout,
                                         transport=self._transport) as client:
                response = await client.post(f"{self._base_url}/api/generate",
                                             json=payload)
                response.raise_for_status()
                text = response.json()["response"]
        except (httpx.HTTPError, KeyError, ValueError) as error:
            raise ProviderError(f"ollama 호출 실패: {error}") from error

        try:
            return json.loads(text)
        except json.JSONDecodeError as error:
            raise ProviderError(f"모델이 JSON 을 내지 않았다: {text[:200]}") from error
