"""스캔 PDF 페이지를 Google Gemini 비전 모델로 읽는 OCR 엔진.

GMS 게이트웨이는 Anthropic·OpenAI만 프록시하고 Google은 포함하지 않는다
(`ai/CLAUDE.md` 참고 — `.env.example`의 GMS 설명) — 그래서 이 어댑터는 GMS를 거치지
않고 Google Generative Language API에 직접 붙는다. 무료 티어가 있는 Flash 계열 모델을
쓴다.

`AnthropicVisionOcr`와 같은 이유로 SDK를 새 의존성으로 넣지 않고 `httpx`로 직접
부른다. `parse_pdf`가 동기 경로이므로 이 호출도 동기(`httpx.Client`)다.

**재시도**: 네트워크 오류·타임아웃과 429/5xx(레이트리밋·과부하)는 일시적이므로 지수
백오프로 재시도한다. 그 외 4xx(400/401/403/404 등 요청 자체가 잘못된 경우)는 다시
불러도 똑같이 실패하므로 즉시 올린다.

**구조화 출력 강제**: Anthropic 어댑터에서 실측으로 확인한 문제(자유 텍스트로 받으면
빈 이미지·저품질 이미지에서 모델이 "글자를 읽을 수 없습니다" 같은 사과문을 프롬프트
지시를 어기고 그대로 내놓는 사례)와 같은 이유로, Gemini의 함수 호출 강제
(`tool_config.function_calling_config.mode = "ANY"`)로 `has_readable_text`(글자
유무)와 `text`(옮겨 적은 본문)를 분리된 필드로 받는다.

**기본 모델 선택 근거(실기동 검증함)**: `gemini-2.0-flash`·`gemini-2.0-flash-001`은
API 상으로는 존재하지만 무료 티어 할당량이 **0으로 배정**돼 있어 매 요청이 429로
막혔다(일반적인 "이번 분 다 씀" 레이트리밋과 달리 시간이 지나도 안 풀린다).
`gemini-2.5-flash`·`gemini-2.5-flash-lite`는 이 계정 기준 "신규 사용자에게 더 이상
제공 안 함"(404)이었다. 실제로 요청이 통과한 건 `gemini-flash-lite-latest` 뿐이라
이걸 기본값으로 쓴다. 이 배정은 프로젝트·시점마다 달라질 수 있으므로, 나중에 다시
막히면 `GEMINI_MODEL` 환경변수로 다른 이름으로 바꾼다.
"""

from __future__ import annotations

import base64
import time

import httpx

from .errors import DocumentParseFailure

_MAX_OUTPUT_TOKENS = 4096
_MAX_ATTEMPTS = 3
_RETRYABLE_STATUS_CODES = frozenset({429, 500, 502, 503, 529})
_BACKOFF_SECONDS = (1.0, 2.0)  # 2번째·3번째 시도 전 대기
_TOOL_NAME = "emit_ocr_text"

# 실기동으로 확인된, 이 무료 티어 계정에서 실제로 요청이 통과하는 모델(2026-08-03).
# gemini-2.0-flash 계열은 할당량이 0으로 막혀 있었다 — 위 docstring 참고.
_DEFAULT_MODEL = "gemini-flash-lite-latest"

# 원문을 그대로 옮기게 한다. 요약·해석을 시키면 OCR 이 아니라 재작성이 된다.
_PROMPT = (
    f"이 이미지는 문서를 스캔한 것이다. 반드시 `{_TOOL_NAME}` 함수를 호출해서 결과를 "
    "돌려줘라. 이미지에 보이는 모든 글자를 위에서 아래로, 왼쪽에서 오른쪽 순서로 그대로 "
    "옮겨 적어 `text` 필드에 담는다. 설명·해석·요약·사과문을 절대 덧붙이지 말고 "
    "본문 텍스트만 옮긴다. 표는 줄바꿈과 공백으로 자연스럽게 옮긴다. 읽을 수 있는 글자가 "
    "전혀 없으면(빈 페이지·판독 불가 포함) `has_readable_text` 를 false 로, `text` 는 "
    "빈 문자열로 채운다 — 이미지 품질에 대한 설명은 하지 않는다."
)
_FUNCTION_DECLARATION = {
    "name": _TOOL_NAME,
    "description": "스캔한 이미지에서 옮겨 적은 본문을 구조화해 돌려준다.",
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "has_readable_text": {
                "type": "BOOLEAN",
                "description": "이미지에 옮겨 적을 수 있는 글자가 있으면 true, 전혀 없으면 false.",
            },
            "text": {
                "type": "STRING",
                "description": (
                    "이미지에 보이는 글자를 그대로 옮겨 적은 것. "
                    "has_readable_text 가 false 면 빈 문자열."
                ),
            },
        },
        "required": ["has_readable_text", "text"],
    },
}


class GeminiVisionOcr:
    """페이지 PNG 이미지를 받아 Gemini `generateContent` API(비전)로 텍스트를 돌려준다."""

    def __init__(self, *, api_key: str, model: str = _DEFAULT_MODEL,
                 base_url: str = "https://generativelanguage.googleapis.com",
                 timeout_seconds: float = 30.0,
                 transport: httpx.BaseTransport | None = None,
                 sleep=time.sleep):
        self._api_key = api_key
        self._model = model
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout_seconds
        self._transport = transport
        self._sleep = sleep

    def ocr_image(self, png_bytes: bytes) -> str:
        payload = {
            "contents": [{"parts": [
                {"text": _PROMPT},
                {"inline_data": {"mime_type": "image/png",
                                 "data": base64.b64encode(png_bytes).decode("ascii")}},
            ]}],
            "tools": [{"function_declarations": [_FUNCTION_DECLARATION]}],
            "tool_config": {"function_calling_config": {
                "mode": "ANY", "allowed_function_names": [_TOOL_NAME]}},
            "generationConfig": {"maxOutputTokens": _MAX_OUTPUT_TOKENS},
        }
        headers = {"x-goog-api-key": self._api_key, "content-type": "application/json"}
        url = f"{self._base_url}/v1beta/models/{self._model}:generateContent"
        body = self._post_with_retry(url, payload, headers)

        candidates = body.get("candidates") or ()
        if not candidates:
            # 안전 필터에 걸리면(`promptFeedback.blockReason`) candidates 가 비어 온다.
            block_reason = (body.get("promptFeedback") or {}).get("blockReason")
            if block_reason:
                raise DocumentParseFailure(
                    "ocr_failed", f"비전 OCR 요청이 차단됐다: {block_reason}")
            return ""

        candidate = candidates[0]
        finish_reason = candidate.get("finishReason")
        if finish_reason == "MAX_TOKENS":
            # 출력 상한에 걸리면 함수 호출 인자가 잘린 채로 올 수 있다. 잘린 결과를 정상
            # 텍스트로 흘려보내지 않고 실패로 바꾼다.
            raise DocumentParseFailure(
                "ocr_failed",
                f"비전 OCR 출력이 상한({_MAX_OUTPUT_TOKENS} 토큰)에 걸려 잘렸다")

        for part in (candidate.get("content") or {}).get("parts") or ():
            call = part.get("functionCall")
            if not call or call.get("name") != _TOOL_NAME:
                continue
            arguments = call.get("args")
            if not isinstance(arguments, dict):
                continue
            if not arguments.get("has_readable_text"):
                return ""
            text = arguments.get("text")
            return text.strip() if isinstance(text, str) else ""

        # 모델이 함수를 안 불렀으면(비정상) 빈 문자열 — `parse_pdf` 가 low_quality 로
        # 흡수한다(기존 Tesseract 빈 결과와 같은 처리). 오류로 올리지 않는다.
        return ""

    def _post_with_retry(self, url: str, payload: dict, headers: dict) -> dict:
        last_error: Exception | None = None
        for attempt in range(_MAX_ATTEMPTS):
            if attempt:
                self._sleep(_BACKOFF_SECONDS[attempt - 1])
            try:
                with httpx.Client(timeout=self._timeout,
                                  transport=self._transport) as client:
                    response = client.post(url, json=payload, headers=headers)
            except httpx.TransportError as error:
                # 연결 실패·타임아웃 — 일시적일 가능성이 높으므로 재시도.
                last_error = error
                continue

            if response.status_code in _RETRYABLE_STATUS_CODES:
                # 429(레이트리밋)·5xx(과부하) — 재시도 대상.
                last_error = httpx.HTTPStatusError(
                    f"retryable status {response.status_code}",
                    request=response.request, response=response)
                continue

            try:
                response.raise_for_status()
                return response.json()
            except (httpx.HTTPError, ValueError) as error:
                # 그 외 4xx(400/401/403/404 등)는 재시도해도 똑같이 실패하므로 즉시 올린다.
                # 예외 문자열에 요청 헤더(키)가 실릴 수 있으므로 타입 이름만 담는다.
                raise DocumentParseFailure(
                    "ocr_failed",
                    f"비전 OCR 호출 실패: {type(error).__name__}") from error

        raise DocumentParseFailure(
            "ocr_failed",
            f"비전 OCR 호출 실패: {type(last_error).__name__} "
            f"({_MAX_ATTEMPTS}회 재시도 후 포기)") from last_error
