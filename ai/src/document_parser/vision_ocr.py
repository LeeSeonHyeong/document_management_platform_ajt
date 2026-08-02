"""스캔 PDF 페이지를 GMS 비전 모델로 읽는 OCR 엔진.

로컬 Tesseract 는 언어 데이터(kor)를 배포 환경에 깔아야 하고, 안 깔린 곳에선 한글 스캔
PDF 가 통째로 실패한다 (S15P11B106-180). 페이지 이미지를 GMS 게이트웨이의 비전 모델에
보내 글자를 받으면 그 로컬 의존이 사라진다. 저렴한 모델(Claude Haiku)로 비용을 줄인다.

`schedule_extractor` 의 Anthropic 어댑터와 같은 이유로 SDK 를 새 의존성으로 넣지 않고
`httpx` 로 직접 부른다. `parse_pdf` 가 동기 경로이므로 이 호출도 동기(`httpx.Client`)다 —
OCR 이 필요한 페이지에만 탄다(네이티브 텍스트 PDF 는 LLM 을 부르지 않는다).
"""

from __future__ import annotations

import base64

import httpx

from .errors import DocumentParseFailure

_API_VERSION = "2023-06-01"
_MAX_TOKENS = 4096

# 원문을 그대로 옮기게 한다. 요약·해석을 시키면 OCR 이 아니라 재작성이 된다.
_PROMPT = (
    "이 이미지는 문서를 스캔한 것이다. 이미지에 보이는 모든 글자를 위에서 아래로, "
    "왼쪽에서 오른쪽 순서로 그대로 옮겨 적어라. 설명·해석·요약을 덧붙이지 말고 "
    "본문 텍스트만 출력한다. 표는 줄바꿈과 공백으로 자연스럽게 옮긴다. "
    "읽을 수 있는 글자가 없으면 아무것도 출력하지 않는다."
)


class AnthropicVisionOcr:
    """페이지 PNG 이미지를 받아 Anthropic Messages API(비전)로 텍스트를 돌려준다."""

    def __init__(self, *, model: str, api_key: str, base_url: str,
                 timeout_seconds: float,
                 transport: httpx.BaseTransport | None = None):
        self._model = model
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout_seconds
        self._transport = transport

    def ocr_image(self, png_bytes: bytes) -> str:
        payload = {
            "model": self._model,
            "max_tokens": _MAX_TOKENS,
            "messages": [{"role": "user", "content": [
                {"type": "image", "source": {
                    "type": "base64", "media_type": "image/png",
                    "data": base64.b64encode(png_bytes).decode("ascii")}},
                {"type": "text", "text": _PROMPT},
            ]}],
        }
        headers = {"x-api-key": self._api_key, "anthropic-version": _API_VERSION,
                   "content-type": "application/json"}
        try:
            with httpx.Client(timeout=self._timeout,
                              transport=self._transport) as client:
                response = client.post(f"{self._base_url}/v1/messages",
                                       json=payload, headers=headers)
                response.raise_for_status()
                body = response.json()
        except (httpx.HTTPError, ValueError) as error:
            # 예외 문자열에 요청 헤더(키)가 실릴 수 있으므로 타입 이름만 담는다.
            raise DocumentParseFailure(
                "ocr_failed", f"비전 OCR 호출 실패: {type(error).__name__}") from error

        # 텍스트 블록을 이어 붙인다. 결과가 없으면 빈 문자열 — `parse_pdf` 가 low_quality 로
        # 흡수한다(기존 Tesseract 빈 결과와 같은 처리). 오류로 올리지 않는다.
        parts = [block.get("text", "") for block in body.get("content") or ()
                 if block.get("type") == "text"]
        return "\n".join(part for part in parts if part).strip()
