"""LLM 경계. 포트가 함수 하나라 어댑터도 가짜도 짧다.

여기서 끝나는 것: 프롬프트를 주면 스키마에 맞는 dict 를 돌려준다.
여기서 하지 않는 것: 재시도·프롬프트 조립·결과 해석. 위층이 한다.
"""

from __future__ import annotations

from typing import Protocol


class ProviderError(Exception):
    """어댑터가 결과를 못 낸 모든 경우 — 전송 실패·타임아웃·스키마 위반.

    이 예외의 문자열에는 URL·모델명·응답 조각이 들어갈 수 있다. 라우터가 이것을 잡아
    고정 메시지로 바꾸고 상세는 로그에만 남긴다 (설계 §3.6).
    """


class JsonCompletionProvider(Protocol):
    async def complete_json(self, prompt: str, schema: dict) -> dict: ...
